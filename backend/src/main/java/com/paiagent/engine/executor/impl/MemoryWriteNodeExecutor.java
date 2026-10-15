package com.paiagent.engine.executor.impl;

import com.paiagent.engine.memory.MemoryWriteRequest;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.service.AgentMemoryService;
import com.paiagent.service.AgentPlanConfigResolver;
import com.paiagent.service.ResolvedAgentPlanConfig;
import com.paiagent.service.VolcengineAgentPlanClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MemoryWriteNodeExecutor extends AbstractAgentPlanNodeExecutor {

    private final AgentPlanConfigResolver configResolver;
    private final VolcengineAgentPlanClient agentPlanClient;
    private final AgentMemoryService memoryService;

    public MemoryWriteNodeExecutor(AgentPlanConfigResolver configResolver,
                                   VolcengineAgentPlanClient agentPlanClient,
                                   AgentMemoryService memoryService) {
        this.configResolver = configResolver;
        this.agentPlanClient = agentPlanClient;
        this.memoryService = memoryService;
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) throws Exception {
        String content = textValue(node, input, "content", "output");
        if (content == null) {
            throw new IllegalArgumentException("写入记忆节点缺少 content");
        }

        ResolvedAgentPlanConfig config = configResolver.resolve(node, "memory");
        List<Double> embedding = List.of();
        if (config.apiUrl() != null && config.apiKey() != null && config.model() != null) {
            embedding = agentPlanClient.createEmbedding(config, content);
        }

        return memoryService.write(MemoryWriteRequest.builder()
                .content(content)
                .memoryType(stringData(node, "memoryType", "fact"))
                .scope(stringData(node, "scope", "workflow"))
                .tags(memoryService.splitTags(stringData(node, "tags", "")))
                .source(node.getId())
                .embedding(embedding)
                .embeddingModel(config.model())
                .sessionId(stringData(node, "sessionId", null))
                .userId(stringData(node, "userId", null))
                .tenantId(stringData(node, "tenantId", null))
                .importance(importanceOf(node))
                .build());
    }

    private Double importanceOf(WorkflowNode node) {
        Object value = node.getData().get("importance");
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = asText(value);
        if (text == null) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String getSupportedNodeType() {
        return "memory_write";
    }
}
