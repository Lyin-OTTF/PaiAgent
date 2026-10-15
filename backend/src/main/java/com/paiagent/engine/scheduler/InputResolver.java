package com.paiagent.engine.scheduler;

import com.paiagent.engine.model.WorkflowNode;

import java.util.Map;

/**
 * 节点输入解析器：将前驱节点输出合并为当前节点输入
 */
@FunctionalInterface
public interface InputResolver {

    /**
     * @param node            待执行节点
     * @param availableOutputs 当前已产出的前驱节点输出快照（只读，可安全并发读取）
     * @return 节点输入
     */
    Map<String, Object> resolve(WorkflowNode node, Map<String, Map<String, Object>> availableOutputs);
}
