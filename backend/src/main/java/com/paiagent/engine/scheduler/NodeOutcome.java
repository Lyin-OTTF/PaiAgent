package com.paiagent.engine.scheduler;

import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.Set;

/**
 * 节点任务执行产物：节点输出 + 条件分支未选中的下游节点声明
 */
@Data
@Builder
public class NodeOutcome {

    /** 节点输出 */
    private Map<String, Object> output;

    /**
     * 条件分支未选中的下游节点（含传递下游）。
     * 由执行适配层依据条件节点输出计算，调度器负责将这些节点标记为 SKIPPED 且不再调度。
     */
    private Set<String> skipDownstream;

    public static NodeOutcome of(Map<String, Object> output) {
        return builder().output(output).build();
    }

    public static NodeOutcome empty() {
        return builder().output(new java.util.LinkedHashMap<>()).build();
    }
}
