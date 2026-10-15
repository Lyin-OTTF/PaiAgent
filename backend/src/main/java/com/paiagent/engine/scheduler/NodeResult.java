package com.paiagent.engine.scheduler;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 单个节点的调度执行结果（与引擎层的 ExecutionResponse.NodeResult 相互转换）
 */
@Data
@Builder
public class NodeResult {

    /** 节点 ID */
    private String nodeId;

    /** 节点类型（作为节点名） */
    private String nodeName;

    /** 终态状态 */
    private NodeStatus status;

    /** 解析后的节点输入 */
    private Map<String, Object> input;

    /** 节点输出（成功时非空） */
    private Map<String, Object> output;

    /** 执行耗时（毫秒） */
    private int duration;

    /** 失败原因（失败时非空） */
    private String error;
}
