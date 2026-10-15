package com.paiagent.engine.agent.gateway;

import com.paiagent.engine.agent.policy.AutonomyLevel;

/**
 * 工具执行上下文（治理层使用）。
 * 描述「这次工具调用」来自哪个租户、被允许的最高自治等级、由谁发起等信息。
 */
public record ToolExecutionContext(
        String tenantId,
        AutonomyLevel allowedLevel,
        String caller,
        Long executionId,
        Long workflowId,
        String nodeId
) {
}
