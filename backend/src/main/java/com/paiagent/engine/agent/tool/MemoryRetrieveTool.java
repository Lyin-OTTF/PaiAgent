package com.paiagent.engine.agent.tool;

import com.paiagent.engine.agent.AgentTool;
import com.paiagent.engine.agent.AgentToolContext;
import com.paiagent.engine.agent.policy.AutonomyLevel;
import com.paiagent.engine.agent.policy.ToolPolicy;
import com.paiagent.engine.memory.MemoryQueryRequest;
import com.paiagent.service.AgentMemoryService;
import com.paiagent.service.AgentPlanConfigResolver;
import com.paiagent.service.ResolvedAgentPlanConfig;
import com.paiagent.service.VolcengineAgentPlanClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ToolPolicy(level = AutonomyLevel.OBSERVE)
public class MemoryRetrieveTool implements AgentTool {

    private final AgentPlanConfigResolver configResolver;
    private final VolcengineAgentPlanClient agentPlanClient;
    private final AgentMemoryService memoryService;

    public MemoryRetrieveTool(AgentPlanConfigResolver configResolver,
                              VolcengineAgentPlanClient agentPlanClient,
                              AgentMemoryService memoryService) {
        this.configResolver = configResolver;
        this.agentPlanClient = agentPlanClient;
        this.memoryService = memoryService;
    }

    @Override
    public String getName() {
        return "memory_retrieve";
    }

    @Override
    public String getDescription() {
        return "从 Agent 长期记忆中召回与当前任务相关的偏好、背景和历史结论。";
    }

    @Override
    public String getInputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"scope\":{\"type\":\"string\",\"description\":\"working/session/user/team\"},\"topK\":{\"type\":\"number\"},\"sessionId\":{\"type\":\"string\"},\"userId\":{\"type\":\"string\"},\"tenantId\":{\"type\":\"string\"}},\"required\":[\"query\"]}";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> arguments, AgentToolContext context) throws Exception {
        String query = String.valueOf(arguments.getOrDefault("query", ""));
        String scope = String.valueOf(arguments.getOrDefault("scope", "workflow"));
        int topK = arguments.get("topK") instanceof Number number ? number.intValue() : 5;
        ResolvedAgentPlanConfig config = configResolver.resolve(context.node(), "memory");
        List<Double> embedding = List.of();
        if (config.apiUrl() != null && config.apiKey() != null && config.model() != null) {
            embedding = agentPlanClient.createEmbedding(config, query);
        }
        return memoryService.retrieve(MemoryQueryRequest.builder()
                .query(query)
                .scope(scope)
                .topK(topK)
                .queryEmbedding(embedding)
                .sessionId(optionalText(arguments, "sessionId"))
                .userId(optionalText(arguments, "userId"))
                .tenantId(optionalText(arguments, "tenantId"))
                .build());
    }

    private String optionalText(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
