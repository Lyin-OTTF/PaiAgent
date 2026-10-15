package com.paiagent.engine.rag;

/**
 * 检索候选：一路检索命中的知识片段。
 * <p>score 语义随路由变化（BM25 分 / cosine / RRF 融合分），最终由 Reranker 归一化重打分，
 * 因此不把任何一路的原始分当作最终排序依据。
 */
public record ScoredChunk(
        Long chunkId,
        Long knowledgeBaseId,
        Long documentId,
        String title,
        String content,
        String sourceUrl,
        String tags,
        double score
) {
    public ScoredChunk withScore(double newScore) {
        return new ScoredChunk(chunkId, knowledgeBaseId, documentId, title, content, sourceUrl, tags, newScore);
    }
}
