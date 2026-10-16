package com.paiagent.engine.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 迭代9 P0-1：工具上下文从执行身份派生会话维度（模型不可指定）。
 */
class AgentToolContextTest {

    @Test
    @DisplayName("有 executionId 时会话 ID 为 exec:<id>")
    void derivesSessionFromExecutionId() {
        AgentToolContext ctx = new AgentToolContext(null, Map.of("executionId", 100, "workflowId", 9));
        assertEquals("100", ctx.executionId());
        assertEquals("9", ctx.workflowId());
        assertEquals("exec:100", ctx.sessionId());
    }

    @Test
    @DisplayName("只有 workflowId 时回退为 wf:<id>")
    void fallsBackToWorkflowId() {
        AgentToolContext ctx = new AgentToolContext(null, Map.of("workflowId", 9L));
        assertNull(ctx.executionId());
        assertEquals("wf:9", ctx.sessionId());
    }

    @Test
    @DisplayName("无执行身份时空值安全（返回 null，不抛异常）")
    void nullSafeWhenNoIdentity() {
        assertNull(new AgentToolContext(null, Map.of()).sessionId());
        assertNull(new AgentToolContext(null, null).sessionId());
        assertNull(new AgentToolContext(null, new LinkedHashMap<>()).executionId());
    }

    @Test
    @DisplayName("空白身份值视为缺失")
    void blankIdentityTreatedAsMissing() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("executionId", "  ");
        input.put("workflowId", "");
        assertNull(new AgentToolContext(null, input).sessionId());
    }
}
