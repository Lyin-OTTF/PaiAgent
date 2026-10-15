package com.paiagent.engine.agent.gateway;

import java.util.Collections;
import java.util.Map;

/**
 * 工具网关执行结果。
 */
public record ToolGatewayResult(
        GatewayStatus status,
        Map<String, Object> output,
        Long approvalId,
        String message
) {

    public static ToolGatewayResult executed(Map<String, Object> output, String message) {
        return new ToolGatewayResult(GatewayStatus.EXECUTED, output, null, message);
    }

    public static ToolGatewayResult denied(String message) {
        return new ToolGatewayResult(GatewayStatus.DENIED, Collections.emptyMap(), null, message);
    }

    public static ToolGatewayResult pendingApproval(Long approvalId, String message) {
        return new ToolGatewayResult(GatewayStatus.PENDING_APPROVAL, Collections.emptyMap(), approvalId, message);
    }
}
