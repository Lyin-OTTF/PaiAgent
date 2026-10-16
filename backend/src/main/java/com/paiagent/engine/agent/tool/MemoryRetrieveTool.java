package com.paiagent.engine.agent.tool;

import com.paiagent.common.UserContext;
import com.paiagent.engine.agent.AgentTool;
import com.paiagent.engine.agent.AgentToolContext;
import com.paiagent.engine.agent.policy.AutonomyLevel;
import com.paiagent.engine.agent.policy.ToolPolicy;
import com.paiagent.engine.agent.tenant.TenantContext;
import com.paiagent.engine.memory.MemoryQueryRequest;
import com.paiagent.service.AgentMemoryService;
import com.paiagent.service.AgentPlanConfigResolver;
import com.paiagent.service.ResolvedAgentPlanConfig;
import com.paiagent.service.VolcengineAgentPlanClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 分层记忆检索工具。
 *
 * <p><b>迭代9（评审报告第九章 🔴1）</b>：{@code userId}/{@code sessionId}/{@code tenantId}
 * 不再取自模型入参（此前输入 schema 明确暴露这三字段，提示注入即可诱导 LLM 以
 * {@code userId=受害者} 读取他人 USER 层记忆）——改为服务端投影：
 * <ul>
 *   <li>{@code userId} ← {@link UserContext#currentOwner()}（登录态，不可伪造）；</li>
 *   <li>{@code tenantId} ← {@link TenantContext}（{@code X-Tenant-Id} 请求头投影）；</li>
 *   <li>{@code sessionId} ← {@link AgentToolContext#sessionId()}（执行身份派生）。</li>
 * </ul>
 */
@Component
@ToolPolicy(level = AutonomyLevel.OBSERVE)
public class MemoryRetrieveTool implements AgentTool {

    /** 未声明租户时的兜底值，与 {@code TenantProjectionInterceptor.DEFAULT_TENANT} 对齐。 */
    private static final String DEFAULT_TENANT = "global";

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
        // 迭代9：不再暴露 userId/sessionId/tenantId —— 归属维度由服务端投影，模型不可指定
        return "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"scope\":{\"type\":\"string\",\"description\":\"working/session/user/team\"},\"topK\":{\"type\":\"number\"}},\"required\":[\"query\"]}";
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
                .sessionId(context.sessionId())
                .userId(UserContext.currentOwner())
                .tenantId(TenantContext.getCurrentOrDefault(DEFAULT_TENANT))
                .build());
    }
}
