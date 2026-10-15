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
 */
@Component
public class DenseRetriever implements Retriever {

    private static final Logger log = LoggerFactory.getLogger(DenseRetriever.class);

    private final KnowledgeChunkMapper chunkMapper;
    private final EmbeddingFunction embeddingFunction;

    /** 桶 key = baseId:dim<维度> -> 索引 */
    private final Map<String, VectorIndex> indexes = new ConcurrentHashMap<>();
    /** 桶 key -> (chunkId -> 元数据) */
    private final Map<String, Map<Long, ScoredChunk>> metadataByBucket = new ConcurrentHashMap<>();

    public DenseRetriever(KnowledgeChunkMapper chunkMapper, EmbeddingFunction embeddingFunction) {
        this.chunkMapper = chunkMapper;
        this.embeddingFunction = embeddingFunction;
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
        VectorIndex index = ensureIndex(knowledgeBaseId, queryVector.length);
        if (index.size() == 0) {
            return List.of();
        }
        List<VectorIndex.ScoredVector> hits = index.search(queryVector, topK);
        String bucketKey = bucketKey(knowledgeBaseId, queryVector.length);
        Map<Long, ScoredChunk> metadata = metadataByBucket.getOrDefault(bucketKey, Map.of());
        List<ScoredChunk> results = new ArrayList<>(hits.size());
        for (VectorIndex.ScoredVector hit : hits) {
            ScoredChunk chunk = metadata.get(hit.id());
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
        indexes.keySet().removeIf(key -> key.startsWith(prefix));
        metadataByBucket.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private VectorIndex ensureIndex(Long knowledgeBaseId, int dimension) {
        String bucketKey = bucketKey(knowledgeBaseId, dimension);
        return indexes.computeIfAbsent(bucketKey, key -> {
            Map<Long, ScoredChunk> metadata = new ConcurrentHashMap<>();
            VectorIndex index = new HnswVectorIndex();
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
            metadataByBucket.put(bucketKey, metadata);
            log.debug("Dense index built: base={} dim={} vectors={}", knowledgeBaseId, dimension, loaded);
            return index;
        });
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
}
