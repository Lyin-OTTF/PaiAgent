package com.paiagent.engine.rag;

import java.util.List;

/**
 * 向量化函数契约：文本 -> embedding 向量。
 * <p>抽象出该接口使 {@link DenseRetriever} 不依赖具体 LLM/Agent Plan 客户端；
 * 生产实现由 service 层装配（复用 AgentPlanConfigResolver + VolcengineAgentPlanClient），
 * 评测/测试可注入确定性实现（如词哈希向量）。空列表表示「无可用向量」，检索路据此降级。
 */
public interface EmbeddingFunction {

    List<Double> embed(Long knowledgeBaseId, String text);
}
