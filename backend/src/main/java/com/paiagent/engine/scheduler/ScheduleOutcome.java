package com.paiagent.engine.scheduler;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 一次 DAG 调度的结果
 */
@Data
@Builder
public class ScheduleOutcome {

    /** 整体状态：SUCCESS / FAILED */
    private String status;

    /** 失败原因（成功为空） */
    private String errorMessage;

    /** 最终输出：拓扑序中最后一个成功节点的输出；无成功节点时为初始输入 */
    private Map<String, Object> finalOutput;

    /** 实际执行过的节点结果（按拓扑序，不含恢复与跳过的节点） */
    private List<NodeResult> nodeResults;

    /** 全部节点的终态（含 SKIPPED / CANCELLED，按拓扑序） */
    private Map<String, NodeStatus> nodeStatuses;

    /** 调度总耗时（毫秒） */
    private long durationMillis;
}
