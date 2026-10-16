package com.paiagent.engine.rag;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.entity.KnowledgeChunk;
import com.paiagent.mapper.KnowledgeChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 迭代3 · 稠密检索路（向量路）：{@link HnswVectorIndex} 近似最近邻 + cosine。
 * <p>索引按「知识库 × 向量维度」分桶懒加载：同库多模型（不同维度）并存时各自成桶，
 * 查询向量按维度命中对应桶，避免混维比较失真。写入侧（upsert/删除分片）经
 * {@link #invalidate(Long)} 失效，下次检索重建。
 * <p>项目未注册 MyBatis-Plus 拦截器，查询需显式声明 {@code deleted = 0}。
 *
 * <p><b>迭代9 P1-9</b>：原先索引与元数据分属两个 {@code ConcurrentHashMap}
 * （{@code indexes} / {@code metadataByBucket}），失效与重建都非原子——并发检索可能读到
 * 「有索引无元数据」或「有元数据无索引」的中间态，表现为结果集凭空缺失（命中向量却拿不到内容）。
 * 现合并为单一 {@link Bucket} 存储，失效/重建都是单次 Map 操作，天然原子。
 */
@Component
public class DenseRetriever implements Retriever {

    private static final Logger log = LoggerFactory.getLogger(DenseRetriever.class);

    /**
     * 缓存桶数量上限（迭代7 · 报告 #15）。
     *
     * <p>「知识库 × 向量维度」组合会让桶无限累积（每个桶还持有全量分片内容与向量，内存可观）。
     * 超限时整体失效、按需重建，避免引入复杂度更高的 LRU 维护。
     */
    private static final int MAX_CACHED_BUCKETS = 32;

    private final KnowledgeChunkMapper chunkMapper;
    private final EmbeddingFunction embeddingFunction;
    private final RagProperties properties;

    /** 桶 key = baseId:dim<维度> -> 索引 + 元数据（迭代9：合并为单桶，失效原子） */
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public DenseRetriever(KnowledgeChunkMapper chunkMapper, EmbeddingFunction embeddingFunction,
                          RagProperties properties) {
        this.chunkMapper = chunkMapper;
        this.embeddingFunction = embeddingFunction;
        this.properties = properties;
    }

    @Override
    public List<ScoredChunk> retrieve(Long knowledgeBaseId, String query, int topK) {
        if (knowledgeBaseId == null || topK <= 0 || query == null || query.isBlank()) {
            return List.of();
        }
        List<Double> embedding = embeddingFunction.embed(knowledgeBaseId, query);
        if (embedding == null || embedding.isEmpty()) {
            return List.of(); // 未配置向量模型或调用失败：词法路兜底
        }
        float[] queryVector = toFloatArray(embedding);
        Bucket bucket = ensureBucket(knowledgeBaseId, queryVector.length);
        if (bucket.index().size() == 0) {
            return List.of();
        }
        List<VectorIndex.ScoredVector> hits = bucket.index().search(queryVector, topK);
        List<ScoredChunk> results = new ArrayList<>(hits.size());
        for (VectorIndex.ScoredVector hit : hits) {
            ScoredChunk chunk = bucket.metadata().get(hit.id());
            if (chunk != null) {
                results.add(chunk.withScore(hit.score()));
            }
        }
        return results;
    }

    /**
     * 使知识库全部分桶失效（分片写入 / 删除 / 重新索引后调用）。
     */
    public void invalidate(Long knowledgeBaseId) {
        String prefix = knowledgeBaseId + ":";
        buckets.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /**
     * 桶数量超限时整体失效（下次检索按需重建），防止内存无界增长。
     */
    private void evictIfOverCapacity() {
        if (buckets.size() < MAX_CACHED_BUCKETS) {
            return;
        }
        log.warn("向量索引缓存桶数已达上限 {}，整体失效后按需重建（避免内存无界增长）", MAX_CACHED_BUCKETS);
        buckets.clear();
    }

    private Bucket ensureBucket(Long knowledgeBaseId, int dimension) {
        String bucketKey = bucketKey(knowledgeBaseId, dimension);
        evictIfOverCapacity();
        return buckets.computeIfAbsent(bucketKey, key -> buildBucket(knowledgeBaseId, dimension));
    }

    /** 构建单桶：索引与元数据一次性构造后整体放入，避免半成品对外可见。 */
    private Bucket buildBucket(Long knowledgeBaseId, int dimension) {
        Map<Long, ScoredChunk> metadata = new ConcurrentHashMap<>();
        // 迭代8（报告🟡8）：容量上限来自配置，默认 RagProperties.DEFAULT_MAX_VECTORS
        VectorIndex index = new HnswVectorIndex(Math.max(1, properties.maxVectors()));
        List<KnowledgeChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getKnowledgeBaseId, knowledgeBaseId)
                .eq(KnowledgeChunk::getStatus, "READY")
                .eq(KnowledgeChunk::getDeleted, 0));
        int loaded = 0;
        for (KnowledgeChunk chunk : chunks) {
            List<Double> embedding = parseEmbedding(chunk.getEmbedding());
            if (embedding.size() != dimension) {
                continue; // 维度不符（模型变更残留）：跳过，保证桶内同维
            }
            index.upsert(chunk.getId(), toFloatArray(embedding));
            metadata.put(chunk.getId(), new ScoredChunk(chunk.getId(), chunk.getKnowledgeBaseId(),
                    chunk.getDocumentId(), chunk.getTitle(), chunk.getContent(), chunk.getSourceUrl(),
                    chunk.getTags(), 0));
            loaded++;
        }
        log.debug("Dense index built: base={} dim={} vectors={}", knowledgeBaseId, dimension, loaded);
        return new Bucket(index, metadata);
    }

    private static String bucketKey(Long knowledgeBaseId, int dimension) {
        return knowledgeBaseId + ":dim" + dimension;
    }

    private static List<Double> parseEmbedding(String embeddingJson) {
        if (!StringUtils.hasText(embeddingJson)) {
            return List.of();
        }
        try {
            return JSON.parseArray(embeddingJson, Double.class);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static float[] toFloatArray(List<Double> values) {
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Double value = values.get(i);
            result[i] = value == null ? 0f : value.floatValue();
        }
        return result;
    }

    /** 单桶：向量索引与其元数据同生共死，失效即整体丢弃。 */
    private record Bucket(VectorIndex index, Map<Long, ScoredChunk> metadata) {
    }
}
