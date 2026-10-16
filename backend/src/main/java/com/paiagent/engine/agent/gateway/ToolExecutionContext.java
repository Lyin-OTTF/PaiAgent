package com.paiagent.engine.agent.gateway;

import com.paiagent.engine.agent.policy.AutonomyLevel;

import java.util.Map;

/**
 * 工具执行上下文（治理层使用）。
 * 描述「这次工具调用」来自哪个租户、被允许的最高自治等级、由谁发起等信息。
 *
 * <p><b>迭代6 HITL 扩展字段</b>：
 * <ul>
 *   <li>{@code suspendContext}：调用方（ReAct 执行器）在转入审批时附带的续跑快照，
 *       网关原样落库到审批记录，审批通过后凭它恢复执行；非审批调用方传 null；</li>
 *   <li>{@code approvalId}：人类已批准的审批单号。网关见到非空且状态为 APPROVED 的
 *       approvalId 时，视为「这一次特定调用已获预授权」，跳过新建审批直接执行
 *       （租户校验/幂等守卫/审计照常生效），并在审计账本上关联该审批单。</li>
 * </ul>
 */
public record ToolExecutionContext(
        String tenantId,
        AutonomyLevel allowedLevel,
        String caller,
        Long executionId,
        Long workflowId,
        String nodeId,
        Map<String, Object> suspendContext,
        Long approvalId
) {

    /** 兼容构造器：无挂起上下文、无预授权审批（治理前行为不变）。 */
    public ToolExecutionContext(String tenantId, AutonomyLevel allowedLevel, String caller,
                                Long executionId, Long workflowId, String nodeId) {
        this(tenantId, allowedLevel, caller, executionId, workflowId, nodeId, null, null);
    }
}
