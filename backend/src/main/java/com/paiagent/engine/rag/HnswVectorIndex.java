package com.paiagent.engine.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 简化版 HNSW（Hierarchical Navigable Small World）内存向量索引，cosine 语义。
 * <p>相对标准 HNSW 的取舍（1 万级片段规模下工程上足够，且行为可预期）：
 * <ul>
 *   <li>邻居选择取最近 M 个，不启用多样性启发式（heuristic neighbor selection）</li>
 *   <li>删除只打 tombstone 不重连（PaiAgent 场景以覆盖写/整库重建为主）</li>
 *   <li>写用可重入锁而非无锁结构：本地单 JVM，写并发低，正确性优先</li>
 * </ul>
 * 参数：M=16、efConstruction=100、efSearch=64、层级衰减 mL=1/ln(M)≈0.607，与启航参考同量级。
 *
 * <p>迭代8（报告🟡8 后半）：新增容量上限 {@link #maxVectors}（默认 {@link #DEFAULT_MAX_VECTORS}，
 * 可由 {@code paiagent.rag.max-vectors} 配置）——索引按知识库懒加载、全量向量常驻内存，
 * 无上限时单桶可达百万级向量，OOM 风险不可控。超限写入显式失败而非静默驱逐：
 * 静默丢向量会让召回率无感劣化，显式失败则可被上层（{@code HybridRetriever} 单路安全兜底）
 * 观测并降级。
 */
public class HnswVectorIndex implements VectorIndex {

    /** 默认容量上限（向量数/桶）。 */
    public static final int DEFAULT_MAX_VECTORS = 100_000;

    private static final int MAX_LEVEL = 16;
    private static final int M = 16;
    private static final int EF_CONSTRUCTION = 100;
    private static final int EF_SEARCH = 64;
    private static final double ML = 1.0 / Math.log(M);

    private final int maxVectors;

    private record DistId(long id, double distance) {
    }

    private static final class Node {
        final Long id;
        final float[] vector;
        final int level;
        final List<Long>[] links;

        @SuppressWarnings("unchecked")
        Node(Long id, float[] vector, int level) {
            this.id = id;
            this.vector = vector;
            this.level = level;
            this.links = new List[level + 1];
            for (int i = 0; i <= level; i++) {
                this.links[i] = new ArrayList<>();
            }
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Long, Node> nodes = new LinkedHashMap<>();
    private final Random random = new Random(20261015L); // 固定种子：索引结构可复现，便于测试与基准对齐
    private Node entry;

    /** 默认容量（{@link #DEFAULT_MAX_VECTORS}）。 */
    public HnswVectorIndex() {
        this(DEFAULT_MAX_VECTORS);
    }

    /**
     * @param maxVectors 容量上限（向量数）；&lt;=0 时按 1 处理，避免配置错误导致无限增长
     */
    public HnswVectorIndex(int maxVectors) {
        this.maxVectors = Math.max(1, maxVectors);
    }

    @Override
    public void upsert(Long id, float[] vector) {
        if (id == null || vector == null || vector.length == 0) {
            return;
        }
        float[] normalized = normalize(vector);
        if (normalized == null) {
            return; // 零向量无方向，跳过
        }
        lock.lock();
        try {
            // 缺陷修复：覆盖写不再"先删后插"。原实现先 nodes.remove(id) 再插入，若覆盖的正是
            // 入口节点，entry 就悬空指向已移出 nodes 的旧对象；旧对象 links 里的 id 会解析为
            // 本次新插入的低层节点，贪心下降仍按旧层级访问 links，即越界。
            // 现改为：旧节点留在变量里作导航起点，新节点先就位，连边后再修正 entry。
            Node replaced = nodes.get(id);
            if (replaced == null && nodes.size() >= maxVectors) {
                // 迭代8（报告🟡8）：新 id 且已满 → 显式失败。同 id 覆盖写不放大内存，允许。
                throw new IllegalStateException(String.format(
                        "向量索引容量上限 %d 已满，拒绝写入 id=%d（可通过 paiagent.rag.max-vectors 调整）",
                        maxVectors, id));
            }
            int level = randomLevel();
            Node node = new Node(id, normalized, level);
            // 新节点必须先就位：后续反向连边后 pruneNeighbors 要能通过 nodes.get(id) 取到它，
            // 否则刚加的这条反向边会被当成悬空 id 剪掉（尝试延后 put 会让 recall 直接崩掉）。
            nodes.put(id, node);
            if (entry == null) {
                entry = node;
                return;
            }
            // 覆盖写时以"被覆盖的旧节点对象"作为导航起点：它虽已不在 nodes 中，但 links 仍指向
            // 图中存活的邻居，可作为下降入口；若改用新节点（links 为空）会退化成孤立点。
            Node ep = replaced != null ? replaced : entry;
            int currentMax = ep.level;
            if (level > currentMax) {
                entry = node;
            }
            // 上层（高于目标层）：贪心下降，只走不连边
            for (int lv = currentMax; lv > level; lv--) {
                ep = greedyClosest(ep, normalized, lv);
            }
            // 目标层及以下：beam 搜索 + 双向连边
            int topLevel = Math.min(level, currentMax);
            List<Long> candidates = new ArrayList<>();
            if (replaced != null) {
                // 覆盖写：id 此刻已指向链接为空的新节点，不能拿它当种子（会被 selectNeighbors
                // 当自环过滤掉），改取旧节点在该层的邻居作为入口，新节点才能接回原图的邻域。
                candidates.addAll(replaced.links[Math.min(topLevel, replaced.level)]);
            } else {
                candidates.add(ep.id);
            }
            for (int lv = topLevel; lv >= 0; lv--) {
                List<Long> beam = beamSearch(candidates, normalized, lv, EF_CONSTRUCTION);
                for (Long neighborId : selectNeighbors(id, beam, normalized, M)) {
                    node.links[lv].add(neighborId);
                    Node neighbor = nodes.get(neighborId);
                    if (neighbor == null || neighbor.level < lv) {
                        continue;
                    }
                    List<Long> neighborLinks = neighbor.links[lv];
                    if (!neighborLinks.contains(id)) {
                        neighborLinks.add(id);
                    }
                    if (neighborLinks.size() > M) {
                        pruneNeighbors(neighbor, lv);
                    }
                }
                candidates = beam;
            }
            if (entry == replaced) {
                // 入口节点被覆盖：入口必须指向存活节点，否则后续 search/upsert 都从图外的
                // 旧对象出发（其 links 指向的 id 已被新节点接管，层级未必匹配）。
                entry = node;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void remove(Long id) {
        if (id == null) {
            return;
        }
        lock.lock();
        try {
            if (!nodes.containsKey(id)) {
                return;
            }
            nodes.remove(id);
            if (entry != null && entry.id.equals(id)) {
                entry = nodes.isEmpty() ? null : nodes.values().iterator().next();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean contains(Long id) {
        lock.lock();
        try {
            return nodes.containsKey(id);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return nodes.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<ScoredVector> search(float[] query, int topK) {
        // 迭代7（报告低危项）：补 query 空值防御——此前 topK 有校验而 query 没有，
        // normalize(null) 会在遍历时抛 NPE。
        if (query == null || query.length == 0 || topK <= 0) {
            return List.of();
        }
        float[] q = normalize(query);
        lock.lock();
        try {
            if (entry == null || q == null) {
                return List.of();
            }
            Node ep = entry;
            for (int lv = ep.level; lv > 0; lv--) {
                ep = greedyClosest(ep, q, lv);
            }
            int ef = Math.max(EF_SEARCH, topK);
            List<Long> beam = beamSearch(new ArrayList<>(List.of(ep.id)), q, 0, ef);
            List<ScoredVector> hits = new ArrayList<>(beam.size());
            for (Long id : beam) {
                Node node = nodes.get(id);
                if (node != null) {
                    hits.add(new ScoredVector(node.id, dot(q, node.vector)));
                }
            }
            hits.sort(Comparator.comparingDouble(ScoredVector::score).reversed());
            return hits.size() > topK ? hits.subList(0, topK) : hits;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 单层贪心：在当前节点的该层邻居里找更近者，直到局部最优。
     *
     * <p>缺陷修复：原实现把 current 移到邻居后，仍按入参 {@code level} 访问
     * {@code current.links[level]}。但邻居的层级可能低于 level——同 id 覆盖写后旧链接会解析到
     * 新插入的低层节点，remove 又只打 tombstone 不重连——此时 links 数组长度不足，直接抛
     * ArrayIndexOutOfBoundsException。现将有效层级逐跳收紧为 {@code min(level, node.level)}，
     * 并把"同一次遍历内连续改进"改为"每轮只移动一步最优邻居"：后者保证移动与层级收紧
     * 在同一次迭代内成对发生，不会出现用旧 lv 读新 current 的情况。
     */
    private Node greedyClosest(Node start, float[] q, int level) {
        Node current = start;
        int effectiveLevel = Math.min(level, current.level);
        double bestDistance = distance(q, current.vector);
        while (true) {
            Long nextId = null;
            double nextDistance = bestDistance;
            for (Long neighborId : current.links[effectiveLevel]) {
                Node neighbor = nodes.get(neighborId);
                if (neighbor == null) {
                    continue;
                }
                double d = distance(q, neighbor.vector);
                if (d < nextDistance) {
                    nextDistance = d;
                    nextId = neighborId;
                }
            }
            if (nextId == null) {
                return current;
            }
            Node next = nodes.get(nextId);
            if (next == null) {
                return current;
            }
            current = next;
            bestDistance = nextDistance;
            effectiveLevel = Math.min(effectiveLevel, current.level);
        }
    }

    /**
     * 单层 beam 搜索：从种子集合出发，返回按距离升序、上限 ef 的候选 id。
     */
    private List<Long> beamSearch(List<Long> seedIds, float[] q, int level, int ef) {
        PriorityQueue<DistId> candidates = new PriorityQueue<>(Comparator.comparingDouble(d -> d.distance));
        PriorityQueue<DistId> results = new PriorityQueue<>(Comparator.comparingDouble(d -> -d.distance));
        Map<Long, DistId> inResults = new HashMap<>();
        Set<Long> visited = new HashSet<>();

        for (Long id : seedIds) {
            if (visited.contains(id)) {
                continue;
            }
            Node node = nodes.get(id);
            // 缺陷修复：该节点可能不存在于本层（覆盖写/删除后残留链接会指向低层 id），
            // 直接读 links[level] 会越界，按"本层没有它"跳过。
            if (node == null || level > node.level) {
                continue;
            }
            visited.add(id);
            DistId di = new DistId(id, distance(q, node.vector));
            candidates.offer(di);
            results.offer(di);
            inResults.put(id, di);
        }
        while (results.size() > ef) {
            inResults.remove(results.poll().id());
        }

        while (!candidates.isEmpty()) {
            DistId nearest = candidates.poll();
            if (nearest.distance > results.peek().distance) {
                break; // 剩余候选不可能比当前最差结果更近，收敛
            }
            Node node = nodes.get(nearest.id());
            // 同上：本层不存在的节点不能展开其 links[level]
            if (node == null || level > node.level) {
                continue;
            }
            for (Long neighborId : node.links[level]) {
                if (visited.contains(neighborId)) {
                    continue;
                }
                visited.add(neighborId);
                Node neighbor = nodes.get(neighborId);
                if (neighbor == null) {
                    continue;
                }
                DistId candidate = new DistId(neighborId, distance(q, neighbor.vector));
                if (candidate.distance() < results.peek().distance() || results.size() < ef) {
                    candidates.offer(candidate);
                    results.offer(candidate);
                    inResults.put(neighborId, candidate);
                    if (results.size() > ef) {
                        inResults.remove(results.poll().id());
                    }
                }
            }
        }
        List<DistId> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.comparingDouble(DistId::distance));
        List<Long> ids = new ArrayList<>(sorted.size());
        for (DistId di : sorted) {
            ids.add(di.id());
        }
        return ids;
    }

    private List<Long> selectNeighbors(Long selfId, List<Long> candidateIds, float[] q, int m) {
        List<DistId> scored = new ArrayList<>(candidateIds.size());
        for (Long id : candidateIds) {
            // 迭代7（报告 #18）：过滤自身——同 id 覆盖写时（先 remove 再 put）其它节点仍保留
            // 指向该 id 的旧链接，beamSearch 可能经旧链接把新节点自己带回候选，
            // 不过滤就会生成自环，劣化图质量与召回。
            if (id == null || id.equals(selfId)) {
                continue;
            }
            Node node = nodes.get(id);
            if (node == null) {
                continue;
            }
            scored.add(new DistId(id, distance(q, node.vector)));
        }
        scored.sort(Comparator.comparingDouble(DistId::distance));
        List<Long> neighbors = new ArrayList<>();
        for (int i = 0; i < Math.min(m, scored.size()); i++) {
            neighbors.add(scored.get(i).id());
        }
        return neighbors;
    }

    private void pruneNeighbors(Node node, int level) {
        List<DistId> scored = new ArrayList<>(node.links[level].size());
        for (Long id : node.links[level]) {
            Node neighbor = nodes.get(id);
            if (neighbor == null) {
                continue;
            }
            scored.add(new DistId(id, distance(node.vector, neighbor.vector)));
        }
        scored.sort(Comparator.comparingDouble(DistId::distance));
        List<Long> pruned = new ArrayList<>();
        for (int i = 0; i < Math.min(M, scored.size()); i++) {
            pruned.add(scored.get(i).id());
        }
        node.links[level].clear();
        node.links[level].addAll(pruned);
    }

    private int randomLevel() {
        double u = random.nextDouble();
        int level = (int) (-Math.log(u) * ML);
        return Math.min(level, MAX_LEVEL - 1);
    }

    private static float[] normalize(float[] vector) {
        double norm = 0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        if (norm <= 0) {
            return null;
        }
        double factor = 1.0 / Math.sqrt(norm);
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = (float) (vector[i] * factor);
        }
        return normalized;
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0;
        int size = Math.min(a.length, b.length);
        for (int i = 0; i < size; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    private static double distance(float[] a, float[] b) {
        return 1.0 - dot(a, b); // 归一化后 cosine 距离
    }
}
