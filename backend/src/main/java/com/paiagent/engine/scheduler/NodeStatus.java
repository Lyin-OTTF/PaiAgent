package com.paiagent.engine.scheduler;

/**
 * 节点执行状态（有界并行 DAG 调度）
 */
public enum NodeStatus {

    /** 待调度 */
    PENDING,

    /** 执行中 */
    RUNNING,

    /** 执行成功 */
    SUCCESS,

    /** 执行失败 */
    FAILED,

    /** 条件分支未选中，被跳过（不执行） */
    SKIPPED,

    /** 已取消（失败快速止损时尚未开始的节点） */
    CANCELLED;

    /**
     * 是否已终结（不再发生变化）
     */
    public boolean isFinished() {
        return this == SUCCESS || this == FAILED || this == SKIPPED || this == CANCELLED;
    }

    /**
     * 是否产出过输出
     */
    public boolean hasOutput() {
        return this == SUCCESS;
    }
}
