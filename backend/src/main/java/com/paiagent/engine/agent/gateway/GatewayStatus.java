package com.paiagent.engine.agent.gateway;

/**
 * 工具网关返回状态。
 */
public enum GatewayStatus {
    /** 已执行 */
    EXECUTED,
    /** 被拒绝（未达执行条件且非审批路径） */
    DENIED,
    /** 已转人工审批，等待批准 */
    PENDING_APPROVAL
}
