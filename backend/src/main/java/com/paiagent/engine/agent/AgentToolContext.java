package com.paiagent.engine.agent;

import com.paiagent.engine.model.WorkflowNode;

import java.util.Map;

/**
 * Execution context exposed to ReAct runtime tools.
 *
 * <p>迭代9（评审报告第九章 🔴1）：新增 {@link #executionId()}/{@link #workflowId()}/
 * {@link #sessionId()} 三个派生访问器。修复前分层记忆工具（memory_retrieve/memory_write）
 * 的 {@code sessionId/userId/tenantId} 全部取自<b>模型入参</b>——提示注入即可诱导 LLM
 * 冒用他人 userId 读他人记忆、向任意租户写入污染记忆。现改为由执行身份（引擎在节点
 * 输入里注入的 executionId/workflowId）派生会话维度，模型无法指定。
 */
public record AgentToolContext(
        WorkflowNode node,
        Map<String, Object> currentInput
) {

    /** 执行身份中的 executionId（字符串形式；缺失返回 null）。 */
    public String executionId() {
        return text("executionId");
    }

    /** 执行身份中的 workflowId（字符串形式；缺失返回 null）。 */
    public String workflowId() {
        return text("workflowId");
    }

    /**
     * 记忆会话 ID：优先 {@code exec:<executionId>}，其次 {@code wf:<workflowId>}，两者皆缺失时返回 null。
     *
     * <p>由执行身份派生而非模型指定，因此模型无法跨会话读取他人 SESSION/WORKING 层记忆。
     */
    public String sessionId() {
        String executionId = executionId();
        if (executionId != null) {
            return "exec:" + executionId;
        }
        String workflowId = workflowId();
        return workflowId != null ? "wf:" + workflowId : null;
    }

    private String text(String key) {
        if (currentInput == null) {
            return null;
        }
        Object value = currentInput.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
