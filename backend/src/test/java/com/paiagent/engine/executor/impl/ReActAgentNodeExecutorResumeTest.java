package com.paiagent.engine.executor.impl;

import com.paiagent.engine.agent.AgentTool;
import com.paiagent.engine.agent.AgentToolContext;
import com.paiagent.engine.agent.AgentToolRegistry;
import com.paiagent.engine.agent.gateway.ToolExecutionContext;
import com.paiagent.engine.agent.gateway.ToolGateway;
import com.paiagent.engine.agent.gateway.ToolGatewayResult;
import com.paiagent.engine.agent.hitl.HitlSuspendContext;
import com.paiagent.engine.agent.policy.AutonomyLevel;
import com.paiagent.engine.llm.ChatClientFactory;
import com.paiagent.engine.llm.PromptTemplateService;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.engine.skill.SkillRegistry;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 迭代6：ReAct 执行器 HITL 挂起 / 续跑测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>挂起：工具转入审批时，治理上下文携带全量挂起快照（可 JSON 还原），
 * *       节点以 approval_required 收尾；</li>
 *   <li>续跑：凭快照从断点下一步继续，pending observation 被替换为补执行产物，
 * *       不重放历史步骤，输出带 resumedFromApproval 标记。</li>
 * </ul>
 */
class ReActAgentNodeExecutorResumeTest {

    private static final String TOOL_CALL_JSON =
            "{\"action\":\"tool_call\",\"reasoningSummary\":\"需要写库\",\"toolName\":\"write_tool\",\"toolInput\":{\"title\":\"周报\"}}";
    private static final String FINAL_ANSWER_JSON =
            "{\"action\":\"final_answer\",\"reasoningSummary\":\"完成\",\"finalAnswer\":\"周报已完成\"}";

    private ReActAgentNodeExecutor executor;
    private ChatClient chatClient;
    private ChatClientFactory chatClientFactory;
    private PromptTemplateService promptTemplateService;
    private AgentToolRegistry toolRegistry;
    private ToolGateway toolGateway;

    @BeforeEach
    void setUp() {
        executor = new ReActAgentNodeExecutor();
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        chatClientFactory = mock(ChatClientFactory.class);
        when(chatClientFactory.createClient(any(), any(), any(), any(), any())).thenReturn(chatClient);
        promptTemplateService = mock(PromptTemplateService.class);
        when(promptTemplateService.processTemplate(any(), any(), any())).thenReturn("请汇总本周数据");
        toolRegistry = mock(AgentToolRegistry.class);
        toolGateway = mock(ToolGateway.class);

        executor.chatClientFactory = chatClientFactory;
        executor.promptTemplateService = promptTemplateService;
        executor.skillRegistry = mock(SkillRegistry.class);
        // toolRegistry / toolGateway 在 ReActAgentNodeExecutor 中为 private，走反射注入
        org.springframework.test.util.ReflectionTestUtils.setField(executor, "toolRegistry", toolRegistry);
        org.springframework.test.util.ReflectionTestUtils.setField(executor, "toolGateway", toolGateway);
        when(toolRegistry.getTools(any())).thenReturn(List.of(fakeTool()));
    }

    private WorkflowNode reactNode() {
        WorkflowNode node = new WorkflowNode();
        node.setId("react-1");
        node.setType("react_agent");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("provider", "openai");
        data.put("apiUrl", "https://api.example.com");
        data.put("apiKey", "key");
        data.put("model", "gpt-test");
        data.put("temperature", 0.5);
        data.put("prompt", "汇总数据");
        data.put("maxSteps", 5);
        data.put("autonomyLevel", "EXECUTE");
        data.put("tools", List.of("write_tool"));
        node.setData(data);
        return node;
    }

    private AgentTool fakeTool() {
        return new AgentTool() {
            @Override public String getName() { return "write_tool"; }
            @Override public String getDescription() { return "d"; }
            @Override public String getInputSchema() { return "{}"; }
            @Override public Map<String, Object> execute(Map<String, Object> args, AgentToolContext ctx) {
                return new LinkedHashMap<>(Map.of("saved", true));
            }
        };
    }

    private ChatResponse chatResponse(String json) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
    }

    // ------------------------------------------------------------------
    // 挂起路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("工具转入审批：治理上下文携带可还原的挂起快照，节点以 approval_required 收尾")
    void shouldAttachSuspendContextWhenToolNeedsApproval() throws Exception {
        when(toolRegistry.getTools(any())).thenReturn(List.of(fakeTool()));
        when(toolRegistry.getRequiredTool("write_tool")).thenReturn(fakeTool());
        when(toolGateway.execute(any(), any(), any(), any()))
                .thenReturn(ToolGatewayResult.pendingApproval(42L, "需要人工审批"));

        when(chatClient.prompt(any(Prompt.class)).call().chatResponse())
                .thenReturn(chatResponse(TOOL_CALL_JSON));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("input", "跑一下周报");
        input.put("tenantId", "tenantA");
        input.put("executionId", 100L);
        input.put("workflowId", 200L);

        Map<String, Object> output = executor.execute(reactNode(), input, null);

        assertEquals("approval_required", output.get("stopReason"));
        assertTrue(String.valueOf(output.get("output")).contains("42"));

        // 捕获网关收到的治理上下文，验证挂起快照
        ArgumentCaptor<ToolExecutionContext> ctxCaptor = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(toolGateway).execute(any(), any(), ctxCaptor.capture(), any());
        ToolExecutionContext captured = ctxCaptor.getValue();
        assertNotNull(captured.suspendContext(), "挂起快照必须随调用下发");
        assertEquals("react-1", captured.suspendContext().get("nodeId"));
        assertEquals(100L, ((Number) captured.suspendContext().get("executionId")).longValue());
        assertEquals("tenantA", captured.suspendContext().get("tenantId"));
        assertEquals(1, ((Number) captured.suspendContext().get("step")).intValue());

        // 快照可 JSON 还原（与 HitlResumeService 的消费端闭环）
        HitlSuspendContext restored = HitlSuspendContext.fromJson(
                JSON.toJSONString(captured.suspendContext()));
        assertEquals("write_tool", restored.toolName());
        assertEquals(AutonomyLevel.EXECUTE, restored.allowedLevel());
        assertEquals("周报", restored.toolInput().get("title"));
        assertEquals("请汇总本周数据", restored.goalPrompt());
        // 快照 trace 含当前 pending 步骤（执行序一致）
        assertEquals(1, restored.trace().size());
        Map<?, ?> traceItem = restored.trace().get(0);
        assertEquals("PENDING_APPROVAL", ((Map<?, ?>) traceItem.get("observation")).get("status"));
    }

    // ------------------------------------------------------------------
    // 续跑路径
    // ------------------------------------------------------------------

    private HitlSuspendContext snapshot() {
        Map<String, Object> toolInput = new LinkedHashMap<>();
        toolInput.put("title", "周报");

        Map<String, Object> pendingItem = new LinkedHashMap<>();
        pendingItem.put("step", 1);
        pendingItem.put("action", "tool_call");
        pendingItem.put("toolName", "write_tool");
        pendingItem.put("toolInput", toolInput);
        Map<String, Object> pendingObservation = new LinkedHashMap<>();
        pendingObservation.put("status", "PENDING_APPROVAL");
        pendingObservation.put("approvalId", 42);
        pendingItem.put("observation", pendingObservation);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("input", "跑一下周报");
        input.put("executionId", 100L);

        return new HitlSuspendContext(
                100L, 200L, "react-1", "tenantA", AutonomyLevel.EXECUTE, "react_agent",
                "write_tool", toolInput, "请汇总本周数据", input,
                new ArrayList<>(List.of(pendingItem)), 1, 5);
    }

    @Test
    @DisplayName("续跑：从断点下一步继续，pending observation 被替换为补执行产物")
    void shouldContinueFromSnapshotWithToolOutputInjected() throws Exception {
        when(chatClient.prompt(any(Prompt.class)).call().chatResponse())
                .thenReturn(chatResponse(FINAL_ANSWER_JSON));

        Map<String, Object> toolOutput = new LinkedHashMap<>(Map.of("saved", true));
        Map<String, Object> output = executor.resumeAfterApproval(
                reactNode(), snapshot(), toolOutput, 42L);

        // 最终答案来自续跑步骤
        assertEquals("周报已完成", output.get("finalAnswer"));
        assertNull(output.get("stopReason"));
        assertEquals(false, output.get("maxStepsReached"));

        // 续跑标记 + 审批单关联（审计可辨）
        assertEquals(true, output.get("resumedFromApproval"));
        assertEquals(42L, ((Number) output.get("approvalId")).longValue());

        // trace：历史 1 步（observation 已替换）+ 新增 final_answer 1 步
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trace = (List<Map<String, Object>>) output.get("toolTrace");
        assertEquals(2, trace.size());
        assertEquals(1, ((Number) trace.get(0).get("step")).intValue());
        assertEquals(toolOutput, trace.get(0).get("observation"));
        assertEquals(2, ((Number) trace.get(1).get("step")).intValue());
        assertEquals("final_answer", trace.get(1).get("action"));

        // 续跑首步即最终答案：提示词里必须带上已注入工具结果的 trace（深桩验证提示词非空即可，
        // 内容正确性由 trace 断言覆盖）
        verify(chatClient, times(1)).prompt(any(Prompt.class));
    }

    @Test
    @DisplayName("续跑：原 trace 不被快照污染（快照可重复用于重试）")
    void shouldNotMutateSnapshotTrace() throws Exception {
        when(chatClient.prompt(any(Prompt.class)).call().chatResponse())
                .thenReturn(chatResponse(FINAL_ANSWER_JSON));

        HitlSuspendContext ctx = snapshot();
        Map<String, Object> originalObservation =
                (Map<String, Object>) ((Map<?, ?>) ctx.trace().get(0)).get("observation");

        executor.resumeAfterApproval(reactNode(), ctx, new LinkedHashMap<>(Map.of("saved", true)), 42L);

        assertEquals("PENDING_APPROVAL", originalObservation.get("status"));
        assertEquals(1, ctx.trace().size());
    }

    @Test
    @DisplayName("续跑：已达步数上限时不再调用 LLM，如实标记 maxStepsReached")
    void shouldNotCallLlmWhenStepsExhausted() throws Exception {
        HitlSuspendContext ctx = new HitlSuspendContext(
                100L, 200L, "react-1", "tenantA", AutonomyLevel.EXECUTE, "react_agent",
                "write_tool", Map.of("title", "周报"), "goal",
                new LinkedHashMap<>(Map.of("input", "x")),
                new ArrayList<>(), 5, 5);

        Map<String, Object> output = executor.resumeAfterApproval(
                reactNode(), ctx, new LinkedHashMap<>(Map.of("saved", true)), 42L);

        verify(chatClient, never()).prompt(any(Prompt.class));
        assertEquals(true, output.get("maxStepsReached"));
        assertEquals(true, output.get("resumedFromApproval"));
    }

    @Test
    @DisplayName("续跑：未配置任何工具时快速失败（配置错误不进入恢复线程）")
    void shouldFailFastWhenNoToolsConfigured() {
        when(toolRegistry.getTools(any())).thenReturn(List.of());
        WorkflowNode node = reactNode();
        Map<String, Object> data = new LinkedHashMap<>(node.getData());
        data.put("tools", List.of());
        node.setData(data);

        assertThrows(IllegalArgumentException.class, () ->
                executor.resumeAfterApproval(node, snapshot(), Map.of(), 42L));
    }
}
