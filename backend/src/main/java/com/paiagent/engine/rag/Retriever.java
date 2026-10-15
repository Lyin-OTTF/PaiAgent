package com.paiagent.engine.rag;

import java.util.List;

/**
 * 单路检索统一契约：按知识库 + 查询返回按该路分数降序的候选。
 * <p>实现方需自行保证降序；任一路返回空列表视为「本路无命中」，由 HybridRetriever 融合处理。
 */
public interface Retriever {

    List<ScoredChunk> retrieve(Long knowledgeBaseId, String query, int topK);
}
