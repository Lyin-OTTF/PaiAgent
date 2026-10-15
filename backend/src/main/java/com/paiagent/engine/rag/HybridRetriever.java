package com.paiagent.engine.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 迭代3 · 混合检索主入口：词法路（BM25）+ 向量路（cosine）双路召回，
 * RRF 融合排名，Reranker 合成 0-1 重排分。
 * <p>设计原则：
 * <ul>
 *   <li>任一路异常/无命中不阻断另一路（safe 包装），两路皆空才返回空，由上层回退旧全表扫描</li>
 *   <li>RRF 只融合排名，天然兼容两路分制差异（BM25 无上界 vs cosine ∈ [-1,1]）</li>
 *   <li>重排分归一到 0-1，检索入口的 scoreThreshold 语义与历史 cosine 阈值兼容</li>
 * </ul>
 */
@Component
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private final Retriever bm25Retriever;
    private final Retriever denseRetriever;
    private final Reranker reranker;
    private final RagProperties properties;

    public HybridRetriever(Retriever bm25Retriever,
                           Retriever denseRetriever,
                           Reranker reranker,
                           RagProperties properties) {
        this.bm25Retriever = bm25Retriever;
        this.denseRetriever = denseRetriever;
        this.reranker = reranker;
        this.properties = properties;
    }

    /**
     * @param scoreThreshold 作用于重排分（0-1）；<=0 时不过滤
     * @return 按重排分降序、不超过 topK 的候选
     */
    public List<ScoredChunk> retrieve(Long knowledgeBaseId, String query, int topK, double scoreThreshold) {
        if (knowledgeBaseId == null || topK <= 0 || query == null || query.isBlank()) {
            return List.of();
        }
        int candidateK = Math.max(topK * Math.max(1, properties.candidateFactor()), topK);
        List<ScoredChunk> lexical = safe("bm25", () -> bm25Retriever.retrieve(knowledgeBaseId, query, candidateK));
        List<ScoredChunk> dense = safe("dense", () -> denseRetriever.retrieve(knowledgeBaseId, query, candidateK));
        if (lexical.isEmpty() && dense.isEmpty()) {
            return List.of();
        }

        Map<Long, Double> rrfScores = RrfFusion.fuse(
                List.of(toIds(lexical), toIds(dense)), Math.max(1, properties.rrfK()));
        Map<Long, ScoredChunk> merged = Reranker.mergeCandidates(List.of(lexical, dense));
        List<ScoredChunk> candidates = new ArrayList<>(merged.size());
        for (ScoredChunk chunk : merged.values()) {
            candidates.add(chunk.withScore(rrfScores.getOrDefault(chunk.chunkId(), 0.0)));
        }
        List<ScoredChunk> ranked = reranker.rerank(query, candidates, rrfScores);

        double threshold = scoreThreshold <= 0 ? 0 : scoreThreshold;
        List<ScoredChunk> result = new ArrayList<>(Math.min(topK, ranked.size()));
        for (ScoredChunk chunk : ranked) {
            if (result.size() >= topK) {
                break;
            }
            if (chunk.score() >= threshold) {
                result.add(chunk);
            }
        }
        return result;
    }

    /**
     * 知识库内容变更后调用：失效 BM25 统计缓存与稠密索引分桶。
     * 融合器依赖 Retriever 接口，仅对具体实现的缓存失效能力做类型探测。
     */
    public void invalidate(Long knowledgeBaseId) {
        if (bm25Retriever instanceof Bm25Retriever bm25) {
            bm25.invalidateStats(knowledgeBaseId);
        }
        if (denseRetriever instanceof DenseRetriever dense) {
            dense.invalidate(knowledgeBaseId);
        }
    }

    private static List<Long> toIds(List<ScoredChunk> chunks) {
        List<Long> ids = new ArrayList<>(chunks.size());
        for (ScoredChunk chunk : chunks) {
            ids.add(chunk.chunkId());
        }
        return ids;
    }

    private static List<ScoredChunk> safe(String route, Supplier<List<ScoredChunk>> action) {
        try {
            List<ScoredChunk> value = action.get();
            return value == null ? List.of() : value;
        } catch (Exception e) {
            // 单路失败不阻断检索：例如 FULLTEXT 索引缺失时向量路仍可服务
            log.warn("Hybrid retrieve route {} failed, fallback to remaining routes: {}", route, e.getMessage());
            return List.of();
        }
    }
}
