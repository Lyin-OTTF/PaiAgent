package com.paiagent.engine.langgraph;

import java.util.Map;

/**
 * 节点执行失败异常（迭代6：fail-stop 语义载体）。
 *
 * <p>与 DAG 引擎（{@code WorkflowEngine}）的失败语义对齐：
 * 任一节点失败即终止整条图执行，不再把失败状态写回后继续路由到下游
 * （旧行为为 fail-continue——失败后的节点照样执行，既消耗无谓的
 * 外部副作用，又让结果集混入语义混乱的节点）。
 *
 * <p>本异常携带失败时刻的<b>状态快照</b>（含已成功节点的 nodeOutputs 与
 * nodeStatuses），供引擎在 catch 分支提取部分执行结果，保证：
 * <ul>
 *   <li>失败节点以 FAILED 状态进入 nodeResults（与 DAG 一致）；</li>
 *   <li>未启动的下游节点不出现在结果中（调度层标记 CANCELLED，不产生结果）；</li>
 *   <li>失败时 outputData 为 null（与 DAG 一致，不把半成品当作最终输出）。</li>
 * </ul>
 */
public class NodeExecutionException extends RuntimeException {

    /** 失败时刻的工作流状态快照（LangGraph state data 的拷贝）。 */
    private final Map<String, Object> stateSnapshot;

    public NodeExecutionException(String message, Throwable cause, Map<String, Object> stateSnapshot) {
        super(message, cause);
        this.stateSnapshot = stateSnapshot;
    }

    public Map<String, Object> getStateSnapshot() {
        return stateSnapshot;
    }
}
