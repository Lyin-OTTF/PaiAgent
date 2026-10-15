package com.paiagent.engine.scheduler;

import com.paiagent.engine.model.WorkflowNode;

import java.util.Map;

/**
 * 节点任务执行器：由引擎层适配，负责快照、事件回调与条件分支跳过计算
 */
@FunctionalInterface
public interface NodeTaskExecutor {

    /**
     * 执行单个节点
     *
     * @param node  待执行节点
     * @param input 已解析的节点输入（前驱输出合并 + 运行时字段）
     * @return 节点执行产物
     * @throws Exception 节点执行失败，由调度器记录为 FAILED 并触发失败传播
     */
    NodeOutcome execute(WorkflowNode node, Map<String, Object> input) throws Exception;
}
