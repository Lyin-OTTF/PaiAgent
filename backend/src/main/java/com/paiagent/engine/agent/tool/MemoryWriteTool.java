package com.paiagent.engine.agent.tool;

import com.paiagent.common.UserContext;
import com.paiagent.engine.agent.AgentTool;
import com.paiagent.engine.agent.AgentToolContext;
import com.paiagent.engine.agent.policy.AutonomyLevel;
import com.paiagent.engine.agent.policy.ToolPolicy;
import com.paiagent.engine.agent.tenant.TenantContext;
import com.paiagent.engine.memory.MemoryWriteRequest;
import com.paiagent.service.AgentMemoryService;
import com.paiagent.service.AgentPlanConfigResolver;
import com.paiagent.service.ResolvedAgentPlanConfig;
import com.paiagent.service.VolcengineAgentPlanClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 分层记忆写入工具。
 *
 * <p><b>迭代9（评审报告第九章 🔴1）</b>：{@code userId}/{@code sessionId}/{@code tenantId}
 * 不再取自模型入参——修复前模型可向任意 userId/租户写入污染记忆。改为服务端投影：
 * {@code userId} ← {@link UserContext#currentOwner()}，{@code tenantId} ← {@link TenantContext}，
 * {@code sessionId} ← {@link AgentToolContext#sessionId()}。
 */
@Component
@ToolPolicy(level = AutonomyLevel.DRAFT, write = true, idempotent = true)
public class MemoryWriteTool implements AgentTool {

    /** 未声明租户时的兜底值，与 {@code TenantProjectionInterceptor.DEFAULT_TENANT} 对齐。 */
    private static final String DEFAULT_TENANT = "global";

    private final AgentPlanConfigResolver configResolver;
    private final VolcengineAgentPlanClient agentPlanClient;
    private final AgentMemoryService memoryService;

    public MemoryWriteTool(AgentPlanConfigResolver configResolver,
                           VolcengineAgentPlanClient agentPlanClient,
                           AgentMemoryService memoryService) {
        this.configResolver = configResolver;
        this.agentPlanClient = agentPlanClient;
        this.memoryService = memoryService;
    }

    @Override
    public String getName() {
        return "memory_write";
    }

    @Override
    public String getDescription() {
        return "显式写入 Agent 长期记忆，适合保存用户偏好、项目背景和稳定结论。";
    }

    @Override
    public String getInputSchema() {
        // 迭代9：不再暴露 userId/sessionId/tenantId —— 归属维度由服务端投影，模型不可指定
        return "{\"type\":\"object\",\"properties\":{\"content\":{\"type\":\"string\"},\"memoryType\":{\"type\":\"string\"},\"scope\":{\"type\":\"string\",\"description\":\"working/session/user/team\"},\"tags\":{\"type\":\"string\"},\"importance\":{\"type\":\"number\"}},\"required\":[\"content\"]}";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> arguments, AgentToolContext context) throws Exception {
        String content = String.valueOf(arguments.getOrDefault("content", ""));
        String scope = String.valueOf(arguments.getOrDefault("scope", "workflow"));
        String memoryType = String.valueOf(arguments.getOrDefault("memoryType", "fact"));
        String tagsText = String.valueOf(arguments.getOrDefault("tags", ""));
        ResolvedAgentPlanConfig config = configResolver.resolve(context.node(), "memory");
        List<Double> embedding = List.of();
        if (config.apiUrl() != null && config.apiKey() != null && config.model() != null) {
            embedding = agentPlanClient.createEmbedding(config, content);
        }
        return memoryService.write(MemoryWriteRequest.builder()
                .content(content)
                .memoryType(memoryType)
                .scope(scope)
                .tags(memoryService.splitTags(tagsText))
                .source(context.node().getId())
                .embedding(embedding)
                .embeddingModel(config.model())
                .sessionId(context.sessionId())
                .userId(UserContext.currentOwner())
                .tenantId(TenantContext.getCurrentOrDefault(DEFAULT_TENANT))
                .importance(optionalImportance(arguments))
                .build());
    }

    private Double optionalImportance(Map<String, Object> arguments) {
        Object value = arguments.get("importance");
        return value instanceof Number number ? number.doubleValue() : null;
    }
}
