package com.paiagent.service;

import com.paiagent.engine.rag.EmbeddingFunction;
import com.paiagent.entity.KnowledgeBase;
import com.paiagent.mapper.KnowledgeBaseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 迭代3 · EmbeddingFunction 生产实现：按知识库绑定的 Agent Plan 配置调用 embedding 接口。
 * <p>与 KnowledgeBaseService.search 的旧逻辑同一数据源（resolveKnowledgeConfig），
 * 保证索引写入与查询向量化口径一致。任何失败（未配置/网络/解析）都返回空列表，
 * 由稠密路降级为「仅词法检索」而非让整个检索失败。
 */
@Component
public class KnowledgeEmbeddingFunction implements EmbeddingFunction {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeEmbeddingFunction.class);

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final AgentPlanConfigResolver configResolver;
    private final VolcengineAgentPlanClient agentPlanClient;

    public KnowledgeEmbeddingFunction(KnowledgeBaseMapper knowledgeBaseMapper,
                                      AgentPlanConfigResolver configResolver,
                                      VolcengineAgentPlanClient agentPlanClient) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.configResolver = configResolver;
        this.agentPlanClient = agentPlanClient;
    }

    @Override
    public List<Double> embed(Long knowledgeBaseId, String text) {
        if (knowledgeBaseId == null || !StringUtils.hasText(text)) {
            return List.of();
        }
        try {
            KnowledgeBase base = knowledgeBaseMapper.selectById(knowledgeBaseId);
            if (base == null) {
                return List.of();
            }
            ResolvedAgentPlanConfig config = configResolver.resolveKnowledgeConfig(base.getConfigId(), base.getEmbeddingModel());
            if (!StringUtils.hasText(config.apiUrl()) || !StringUtils.hasText(config.apiKey()) || !StringUtils.hasText(config.model())) {
                return List.of();
            }
            List<Double> embedding = agentPlanClient.createEmbedding(config, text);
            return embedding == null ? List.of() : embedding;
        } catch (Exception e) {
            log.warn("Embedding failed for base {}: {}", knowledgeBaseId, e.getMessage());
            return List.of();
        }
    }
}
