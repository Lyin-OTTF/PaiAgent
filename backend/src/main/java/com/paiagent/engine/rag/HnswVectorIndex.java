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
 */
public class HnswVectorIndex implements VectorIndex {

    private static final int MAX_LEVEL = 16;
    private static final int M = 16;
    private static final int EF_CONSTRUCTION = 100;
    private static final int EF_SEARCH = 64;
    private static final double ML = 1.0 / Math.log(M);

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
            nodes.remove(id);
            int level = randomLevel();
            Node node = new Node(id, normalized, level);
            nodes.put(id, node);
            if (entry == null) {
                entry = node;
                return;
            }
            Node ep = entry;
            int currentMax = ep.level;
            if (level > currentMax) {
                entry = node;
            }
            // 上层（高于目标层）：贪心下降，只走不连边
            for (int lv = currentMax; lv > level; lv--) {
                ep = greedyClosest(ep, normalized, lv);
            }
            // 目标层及以下：beam 搜索 + 双向连边
            List<Long> candidates = new ArrayList<>();
            candidates.add(ep.id);
            for (int lv = Math.min(level, currentMax); lv >= 0; lv--) {
                List<Long> beam = beamSearch(candidates, normalized, lv, EF_CONSTRUCTION);
                for (Long neighborId : selectNeighbors(beam, normalized, M)) {
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
        if (topK <= 0) {
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
     */
    private Node greedyClosest(Node start, float[] q, int level) {
        Node current = start;
        double bestDistance = distance(q, current.vector);
        boolean improved = true;
        while (improved) {
            improved = false;
            for (Long neighborId : current.links[level]) {
                Node neighbor = nodes.get(neighborId);
                if (neighbor == null) {
                    continue;
                }
                double d = distance(q, neighbor.vector);
                if (d < bestDistance) {
                    bestDistance = d;
                    current = neighbor;
                    improved = true;
                }
            }
        }
        return current;
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
            if (node == null) {
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
            if (node == null) {
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

    private List<Long> selectNeighbors(List<Long> candidateIds, float[] q, int m) {
        List<DistId> scored = new ArrayList<>(candidateIds.size());
        for (Long id : candidateIds) {
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
