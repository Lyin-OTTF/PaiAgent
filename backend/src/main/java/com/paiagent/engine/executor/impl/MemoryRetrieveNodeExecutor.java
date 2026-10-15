package com.paiagent.engine.executor.impl;

import com.paiagent.engine.memory.MemoryQueryRequest;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.service.AgentMemoryService;
import com.paiagent.service.AgentPlanConfigResolver;
import com.paiagent.service.ResolvedAgentPlanConfig;
import com.paiagent.service.VolcengineAgentPlanClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MemoryRetrieveNodeExecutor extends AbstractAgentPlanNodeExecutor {

    private final AgentPlanConfigResolver configResolver;
    private final VolcengineAgentPlanClient agentPlanClient;
    private final AgentMemoryService memoryService;

    public MemoryRetrieveNodeExecutor(AgentPlanConfigResolver configResolver,
                                      VolcengineAgentPlanClient agentPlanClient,
                                      AgentMemoryService memoryService) {
        this.configResolver = configResolver;
        this.agentPlanClient = agentPlanClient;
        this.memoryService = memoryService;
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) throws Exception {
        String query = textValue(node, input, "query", "input");
        if (query == null) {
            throw new IllegalArgumentException("召回记忆节点缺少 query");
        }

        ResolvedAgentPlanConfig config = configResolver.resolve(node, "memory");
        List<Double> embedding = List.of();
        if (config.apiUrl() != null && config.apiKey() != null && config.model() != null) {
            embedding = agentPlanClient.createEmbedding(config, query);
        }

        Map<String, Object> output = memoryService.retrieve(MemoryQueryRequest.builder()
                .query(query)
                .scope(stringData(node, "scope", "workflow"))
                .tags(memoryService.splitTags(stringData(node, "tags", "")))
                .queryEmbedding(embedding)
                .topK(intData(node, "topK", 5))
                .sessionId(stringData(node, "sessionId", null))
                .userId(stringData(node, "userId", null))
                .tenantId(stringData(node, "tenantId", null))
                .build());
        output.put("output", output.get("context"));
        return output;
    }

    @Override
    public String getSupportedNodeType() {
        return "memory_retrieve";
    }
}
