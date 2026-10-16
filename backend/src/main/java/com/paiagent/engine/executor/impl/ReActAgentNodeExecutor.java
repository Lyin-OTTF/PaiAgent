package com.paiagent.engine.executor.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.agent.AgentTool;
import com.paiagent.engine.agent.AgentToolContext;
import com.paiagent.engine.agent.AgentToolRegistry;
import com.paiagent.engine.agent.gateway.GatewayStatus;
import com.paiagent.engine.agent.gateway.ToolGateway;
import com.paiagent.engine.agent.gateway.ToolGatewayResult;
import com.paiagent.engine.agent.gateway.ToolExecutionContext;
import com.paiagent.engine.agent.hitl.HitlSuspendContext;
import com.paiagent.engine.agent.policy.AutonomyLevel;
import com.paiagent.engine.agent.tenant.TenantContext;
import com.paiagent.engine.llm.LLMNodeConfig;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.engine.skill.Skill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * First-phase ReAct runtime implemented inside a workflow node.
 */
@Slf4j
@Component
public class ReActAgentNodeExecutor extends AbstractLLMNodeExecutor {

    private static final int DEFAULT_MAX_STEPS = 5;
    private static final int MAX_ALLOWED_STEPS = 20;

    @Autowired
    private AgentToolRegistry toolRegistry;

    @Autowired
    private ToolGateway toolGateway;

    @Override
    protected String getNodeType() {
        return "react_agent";
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) throws Exception {
        return execute(node, input, null);
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input,
                                       Consumer<ExecutionEvent> progressCallback) throws Exception {
        ReActRuntime runtime = prepareRuntime(node, input, null, null);
        return runReActLoop(runtime, input, new ArrayList<>(), new TokenUsage(), 1, progressCallback);
    }

    /**
     * 迭代6：HITL 审批通过后的断点续跑（resume）。
     *
     * <p>凭审批记录里的挂起快照重建运行时（目标提示词直接用快照中已渲染的版本，
     * 保证与挂起时刻一致），把被挂起那一步的 observation 替换为补执行产物，
     * 从断点的下一步继续 ReAct 循环，产出最终答案。
     *
     * <p>语义约定：
     * <ul>
     *   <li>tokenUsage 从零累计——挂起前的消耗已记录在原执行输出中，此处只计续跑新增；</li>
     *   <li>输出标记 {@code resumedFromApproval=true} 与 {@code approvalId}，便于前端/审计辨识；</li>
     *   <li>不重放已执行步骤——快照 trace 即历史，续跑只追加新步骤，无重复副作用。</li>
     * </ul>
     */
    public Map<String, Object> resumeAfterApproval(WorkflowNode node, HitlSuspendContext snapshot,
                                                   Map<String, Object> toolOutput, Long approvalId) throws Exception {
        ToolExecutionContext execCtx = new ToolExecutionContext(
                snapshot.tenantId(), snapshot.allowedLevel(), snapshot.caller(),
                snapshot.executionId(), snapshot.workflowId(), snapshot.nodeId());
        ReActRuntime runtime = prepareRuntime(node, snapshot.input(), snapshot.goalPrompt(), execCtx);
        List<Map<String, Object>> trace = restoreTrace(snapshot.trace(), toolOutput);
        Map<String, Object> output = runReActLoop(runtime, snapshot.input(), trace,
                new TokenUsage(), snapshot.step() + 1, null);
        output.put("resumedFromApproval", true);
        if (approvalId != null) {
            output.put("approvalId", approvalId);
        }
        return output;
    }

    /**
     * 构建 ReAct 运行时（配置/目标/工具/系统提示/ChatClient/治理上下文）。
     *
     * @param goalPromptOverride 挂起快照中已渲染的目标提示词（resume 时传入，保证与挂起时刻一致）
     * @param execCtxOverride    挂起快照中的治理上下文（resume 时传入：租户/等级/执行身份以快照为准）
     */
    private ReActRuntime prepareRuntime(WorkflowNode node, Map<String, Object> input,
                                        String goalPromptOverride, ToolExecutionContext execCtxOverride) {
        LLMNodeConfig config = extractConfig(node);
        validateConfig(config);

        String goalPrompt = goalPromptOverride != null
                ? goalPromptOverride
                : promptTemplateService.processTemplate(config.getPromptTemplate(), config.getInputParams(), input);
        int maxSteps = resolveMaxSteps(node.getData());
        List<AgentTool> availableTools = toolRegistry.getTools(resolveToolNames(node.getData()));
        if (availableTools.isEmpty()) {
            throw new IllegalArgumentException("ReAct Agent 至少需要启用一个工具");
        }

        String systemPrompt = buildSystemPrompt(config, availableTools, node.getData());
        ChatClient chatClient = chatClientFactory.createClient(
                config.getProvider(),
                config.getApiUrl(),
                config.getApiKey(),
                config.getModel(),
                config.getTemperature()
        );
        ToolExecutionContext execCtx = execCtxOverride != null ? execCtxOverride : buildExecutionContext(node, input);
        AgentToolContext toolContext = new AgentToolContext(node, input);
        return new ReActRuntime(node, config, goalPrompt, maxSteps, availableTools,
                systemPrompt, chatClient, execCtx, toolContext);
    }

    /**
     * ReAct 主循环（首次执行与审批后续跑共用）。
     *
     * @param trace      初始 trace（首次执行为空；续跑为挂起快照恢复出的历史）
     * @param tokenUsage token 累计器（首次执行新建；续跑从零累计新增消耗）
     * @param startStep  起始步号（首次执行为 1；续跑为挂起步 + 1）
     */
    private Map<String, Object> runReActLoop(ReActRuntime runtime, Map<String, Object> input,
                                             List<Map<String, Object>> trace, TokenUsage tokenUsage,
                                             int startStep, Consumer<ExecutionEvent> progressCallback) {
        for (int step = startStep; step <= runtime.maxSteps(); step++) {
            String userPrompt = buildStepPrompt(runtime.goalPrompt(), input, trace, step, runtime.maxSteps());
            ChatResponse response = runtime.chatClient().prompt(new Prompt(List.of(
                    new SystemMessage(runtime.systemPrompt()),
                    new UserMessage(userPrompt)
            ))).call().chatResponse();

            tokenUsage.add(response);
            String content = response.getResult().getOutput().getContent();
            AgentDecision decision = parseDecision(content);

            Map<String, Object> traceItem = new LinkedHashMap<>();
            traceItem.put("step", step);
            traceItem.put("action", decision.action());
            traceItem.put("reasoningSummary", decision.reasoningSummary());

            if ("final_answer".equals(decision.action())) {
                traceItem.put("finalAnswer", decision.finalAnswer());
                trace.add(traceItem);
                emitProgress(runtime.node(), progressCallback, "ReAct 完成", traceItem);
                return buildOutput(decision.finalAnswer(), trace, tokenUsage, false, null);
            }

            if (!"tool_call".equals(decision.action())) {
                traceItem.put("finalAnswer", content);
                trace.add(traceItem);
                emitProgress(runtime.node(), progressCallback, "ReAct 返回非标准动作，按最终答案处理", traceItem);
                return buildOutput(content, trace, tokenUsage, false, null);
            }

            String toolName = decision.toolName();
            AgentTool tool = toolRegistry.getRequiredTool(toolName);
            Map<String, Object> toolInput = decision.toolInput();

            // 迭代6：trace 项先定型再发调用（observation 由网关回填），
            // 保证一旦转入审批，挂起快照中的 trace 与真实执行序一致。
            traceItem.put("toolName", toolName);
            traceItem.put("toolInput", toolInput);
            trace.add(traceItem);

            Map<String, Object> observation;
            try {
                // 迭代6：携带挂起上下文——若网关判定需审批，快照随审批记录落库，供恢复锚定
                ToolExecutionContext callCtx = withSuspendContext(runtime, input, trace, step, toolName, toolInput);
                ToolGatewayResult gatewayResult = toolGateway.execute(tool, toolInput, callCtx, runtime.toolContext());
                if (gatewayResult.status() == GatewayStatus.EXECUTED) {
                    observation = gatewayResult.output();
                } else if (gatewayResult.status() == GatewayStatus.DENIED) {
                    observation = deniedObservation(gatewayResult.message());
                } else {
                    // PENDING_APPROVAL：工具需人工审批，本轮无法继续执行，以待审批状态收尾
                    return suspendForApproval(runtime, traceItem, trace, toolName,
                            gatewayResult, tokenUsage, progressCallback);
                }
            } catch (Exception toolError) {
                log.warn("ReAct 工具调用失败: node={}, tool={}, error={}", runtime.node().getId(), toolName, toolError.getMessage());
                observation = errorObservation(toolError.getMessage());
            }

            traceItem.put("observation", observation);
            emitProgress(runtime.node(), progressCallback, "ReAct 工具调用: " + toolName, traceItem);
        }

        String message = "达到最大 ReAct 步数限制: " + runtime.maxSteps();
        return buildOutput(message, trace, tokenUsage, true, message);
    }

    /**
     * 迭代6：为本次工具调用附带挂起上下文快照。
     * 快照在每次工具调用前构建（成本仅一次小 Map 序列化），只有网关判定转入审批时
     * 才会被落库；未挂起时随调用结束丢弃。
     */
    private ToolExecutionContext withSuspendContext(ReActRuntime runtime, Map<String, Object> input,
                                                    List<Map<String, Object>> trace, int step,
                                                    String toolName, Map<String, Object> toolInput) {
        ToolExecutionContext base = runtime.execCtx();
        HitlSuspendContext snapshot = new HitlSuspendContext(
                base.executionId(), base.workflowId(), runtime.node().getId(),
                base.tenantId(), base.allowedLevel(), base.caller(),
                toolName, toolInput, runtime.goalPrompt(), input, trace, step, runtime.maxSteps());
        return new ToolExecutionContext(
                base.tenantId(), base.allowedLevel(), base.caller(),
                base.executionId(), base.workflowId(), base.nodeId(),
                snapshot.toContextMap(), null);
    }

    /**
     * 迭代6：工具转入人工审批的收尾——回填 observation 并以 approval_required 结束本轮循环。
     * 挂起快照已在调用前随 ToolExecutionContext 落入审批记录，审批通过后由
     * HitlResumeService 调 {@link #resumeAfterApproval} 续跑。
     */
    private Map<String, Object> suspendForApproval(ReActRuntime runtime, Map<String, Object> traceItem,
                                                   List<Map<String, Object>> trace, String toolName,
                                                   ToolGatewayResult gatewayResult, TokenUsage tokenUsage,
                                                   Consumer<ExecutionEvent> progressCallback) {
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("status", "PENDING_APPROVAL");
        pending.put("approvalId", gatewayResult.approvalId());
        pending.put("message", gatewayResult.message());
        traceItem.put("observation", pending);
        emitProgress(runtime.node(), progressCallback, "ReAct 工具需人工审批: " + toolName, traceItem);
        return buildOutput("工具调用需要人工审批（审批单 #" + gatewayResult.approvalId() + "）："
                + gatewayResult.message(), trace, tokenUsage, false, "approval_required");
    }

    /**
     * 迭代6：从挂起快照恢复 trace，并把被挂起那一步的 observation 替换为补执行产物。
     * 快照 trace 的最后一项即 pending 步骤（调用前已定型），替换后与「正常执行过该工具」
     * 的历史完全同构，续跑步骤无缝衔接。
     */
    private List<Map<String, Object>> restoreTrace(List<Map<String, Object>> snapshotTrace,
                                                   Map<String, Object> toolOutput) {
        List<Map<String, Object>> trace = new ArrayList<>();
        if (snapshotTrace != null) {
            for (Map<String, Object> item : snapshotTrace) {
                trace.add(new LinkedHashMap<>(item));
            }
        }
        if (!trace.isEmpty()) {
            trace.get(trace.size() - 1).put("observation", toolOutput != null ? toolOutput : Map.of());
        }
        return trace;
    }

    private Map<String, Object> deniedObservation(String message) {
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("error", message);
        observation.put("gateway", "DENIED");
        observation.put("recoverable", false);
        return observation;
    }

    private Map<String, Object> errorObservation(String message) {
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("error", message);
        observation.put("recoverable", true);
        return observation;
    }

    private String buildSystemPrompt(LLMNodeConfig config, List<AgentTool> tools, Map<String, Object> nodeData) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个运行在 PaiAgent 工作流节点里的 ReAct Agent。\n");
        sb.append("你必须通过 JSON 决策进行工具调用或给出最终答案，不要输出 Markdown 代码块。\n");
        sb.append("不要暴露完整隐藏推理链，只在 reasoningSummary 字段给一句简短行动理由。\n\n");

        if (hasText(nodeData.get("knowledgeBaseId"))) {
            sb.append("当前节点已配置知识库。回答项目资料、实现计划、配置字段或节点说明相关问题时，必须先调用 knowledge_retrieve 检索知识库，再基于 observation 回答。\n\n");
        }

        if (config.getSkillName() != null && !config.getSkillName().isBlank()) {
            Optional<Skill> skill = skillRegistry.getSkill(config.getSkillName());
            skill.ifPresent(value -> {
                sb.append("当前节点关联 Skill: ").append(value.getName()).append("\n");
                sb.append(value.getSummary()).append("\n\n");
            });
        }

        sb.append("可用工具:\n");
        for (AgentTool tool : tools) {
            sb.append("- name: ").append(tool.getName()).append("\n");
            sb.append("  description: ").append(tool.getDescription()).append("\n");
            sb.append("  inputSchema: ").append(tool.getInputSchema()).append("\n");
        }

        sb.append("""

                每次只返回下面两种 JSON 之一：
                {"action":"tool_call","reasoningSummary":"为什么需要这个工具","toolName":"工具名","toolInput":{}}
                {"action":"final_answer","reasoningSummary":"为什么可以结束","finalAnswer":"最终答案"}
                """);
        return sb.toString();
    }

    private String buildStepPrompt(String goalPrompt, Map<String, Object> input,
                                   List<Map<String, Object>> trace, int step, int maxSteps) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("goal", goalPrompt);
        payload.put("currentInput", input);
        payload.put("previousSteps", trace);
        payload.put("step", step);
        payload.put("maxSteps", maxSteps);
        return JSON.toJSONString(payload);
    }

    private AgentDecision parseDecision(String content) {
        String cleaned = stripCodeFence(content);
        try {
            JSONObject json = JSON.parseObject(cleaned);
            String action = normalizeAction(json.getString("action"));
            String reasoningSummary = defaultString(json.getString("reasoningSummary"));
            String toolName = firstString(json, "toolName", "tool_name");
            Map<String, Object> toolInput = toMap(firstValue(json, "toolInput", "tool_input", "arguments"));
            String finalAnswer = defaultString(firstString(json, "finalAnswer", "final_answer", "answer", "content"));
            return new AgentDecision(action, reasoningSummary, toolName, toolInput, finalAnswer);
        } catch (JSONException | IllegalArgumentException e) {
            log.warn("ReAct 响应不是合法 JSON，按最终答案处理: {}", e.getMessage());
            return new AgentDecision("final_answer", "模型直接返回了最终内容", null, Map.of(), content);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof JSONObject jsonObject) {
            return new LinkedHashMap<>(jsonObject);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        return JSON.parseObject(JSON.toJSONString(value), Map.class);
    }

    private String stripCodeFence(String content) {
        if (content == null) {
            return "";
        }

        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }

        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNewline >= 0 && lastFence > firstNewline) {
            return trimmed.substring(firstNewline + 1, lastFence).trim();
        }
        return trimmed;
    }

    private String normalizeAction(String action) {
        if (action == null || action.isBlank()) {
            return "final_answer";
        }
        String normalized = action.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "tool", "call_tool", "function_call" -> "tool_call";
            case "final", "answer" -> "final_answer";
            default -> normalized;
        };
    }

    private Object firstValue(JSONObject json, String... names) {
        for (String name : names) {
            if (json.containsKey(name)) {
                return json.get(name);
            }
        }
        return null;
    }

    private String firstString(JSONObject json, String... names) {
        Object value = firstValue(json, names);
        return value == null ? null : value.toString();
    }

    private Map<String, Object> buildOutput(String finalAnswer, List<Map<String, Object>> trace,
                                            TokenUsage tokenUsage, boolean maxStepsReached,
                                            String stopReason) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("output", finalAnswer);
        output.put("finalAnswer", finalAnswer);
        output.put("toolTrace", trace);
        output.put("steps", trace.size());
        output.put("maxStepsReached", maxStepsReached);
        if (stopReason != null) {
            output.put("stopReason", stopReason);
        }
        output.put("inputTokens", tokenUsage.inputTokens);
        output.put("outputTokens", tokenUsage.outputTokens);
        output.put("totalTokens", tokenUsage.totalTokens);
        output.put("tokens", tokenUsage.totalTokens);
        return output;
    }

    private void emitProgress(WorkflowNode node, Consumer<ExecutionEvent> progressCallback,
                              String message, Map<String, Object> data) {
        if (progressCallback != null) {
            progressCallback.accept(ExecutionEvent.nodeProgress(node.getId(), node.getType(), message, data));
        }
    }

    private int resolveMaxSteps(Map<String, Object> data) {
        Object value = data == null ? null : data.get("maxSteps");
        if (value == null) {
            return DEFAULT_MAX_STEPS;
        }

        int maxSteps = value instanceof Number number
                ? number.intValue()
                : Integer.parseInt(value.toString());
        return Math.max(1, Math.min(MAX_ALLOWED_STEPS, maxSteps));
    }

    private List<String> resolveToolNames(Map<String, Object> data) {
        Object value = data == null ? null : data.get("tools");
        List<String> toolNames;
        if (value == null) {
            toolNames = new ArrayList<>();
        } else if (value instanceof List<?> list) {
            toolNames = list.stream()
                    .map(Object::toString)
                    .filter(toolName -> !toolName.isBlank())
                    .collect(Collectors.toList());
        } else {
            toolNames = List.of(value.toString().split(",")).stream()
                    .map(String::trim)
                    .filter(toolName -> !toolName.isBlank())
                    .collect(Collectors.toList());
        }

        if (hasText(data == null ? null : data.get("knowledgeBaseId")) && !toolNames.contains("knowledge_retrieve")) {
            toolNames = new ArrayList<>(toolNames);
            toolNames.add("knowledge_retrieve");
        }
        if (toolNames.contains("web_search") && !toolNames.contains("web_fetch")) {
            List<String> expanded = new ArrayList<>(toolNames);
            expanded.add("web_fetch");
            return expanded;
        }
        return toolNames;
    }

    private boolean hasText(Object value) {
        return value != null && !String.valueOf(value).trim().isEmpty();
    }

    /**
     * 构造工具治理执行上下文。
     * 运行时允许的最高自治等级来自节点配置 autonomyLevel（缺省 EXECUTE，保证既有工作流行为不变）；
     * tenantId 解析优先级（迭代6）：
     * <ol>
     *   <li>input 显式携带的 tenantId（上游引擎/测试直注入）；</li>
     *   <li>{@link TenantContext} 当前线程租户（由 TenantProjectionInterceptor 从
     *       {@code X-Tenant-Id} 请求头投影，服务端可信来源）；</li>
     *   <li>兜底 "global"（与治理前行为一致）。</li>
     * </ol>
     * 修复前：input 从不含 tenantId 且无投影机制，租户恒为 "global"，
     * ToolGateway 跨租户校验形同虚设。
     */
    private ToolExecutionContext buildExecutionContext(WorkflowNode node, Map<String, Object> input) {
        String nodeId = node.getId();
        String tenantId = resolveTenantId(input);
        AutonomyLevel allowedLevel = resolveAllowedLevel(node.getData());
        String caller = "react_agent";
        Long executionId = asLong(input.get("executionId"));
        Long workflowId = asLong(input.get("workflowId"));
        return new ToolExecutionContext(tenantId, allowedLevel, caller, executionId, workflowId, nodeId);
    }

    /**
     * 解析租户：input 显式值 &gt; TenantContext 线程投影 &gt; "global"。
     * input 里的值仅作为直调/测试通道保留——HTTP 链路上租户只认请求头投影，
     * 因为 input 内容可被模型/上游节点影响，不是可信来源。
     */
    public static String resolveTenantId(Map<String, Object> input) {
        Object explicit = input == null ? null : input.get("tenantId");
        if (explicit != null && !String.valueOf(explicit).isBlank()) {
            return String.valueOf(explicit).trim();
        }
        return TenantContext.getCurrentOrDefault("global");
    }

    private AutonomyLevel resolveAllowedLevel(Map<String, Object> data) {
        Object raw = data == null ? null : data.get("autonomyLevel");
        if (raw != null && !String.valueOf(raw).isBlank()) {
            return AutonomyLevel.fromName(raw.toString());
        }
        return AutonomyLevel.EXECUTE;
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void validateConfig(LLMNodeConfig config) {
        if (isBlank(config.getProvider())) {
            throw new IllegalArgumentException("ReAct Agent 缺少有效的提供商配置");
        }
        if (isBlank(config.getApiUrl()) || isBlank(config.getApiKey()) || isBlank(config.getModel())) {
            throw new IllegalArgumentException("ReAct Agent 缺少有效的模型配置，请检查全局配置或节点配置");
        }
        if (isBlank(config.getPromptTemplate())) {
            throw new IllegalArgumentException("ReAct Agent 缺少任务提示词");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private record AgentDecision(
            String action,
            String reasoningSummary,
            String toolName,
            Map<String, Object> toolInput,
            String finalAnswer
    ) {
    }

    /**
     * ReAct 运行时快照（迭代6）：首次执行与审批后续跑共用的不可变执行上下文。
     */
    private record ReActRuntime(
            WorkflowNode node,
            LLMNodeConfig config,
            String goalPrompt,
            int maxSteps,
            List<AgentTool> availableTools,
            String systemPrompt,
            ChatClient chatClient,
            ToolExecutionContext execCtx,
            AgentToolContext toolContext
    ) {
    }

    private static class TokenUsage {
        private int inputTokens;
        private int outputTokens;
        private int totalTokens;

        void add(ChatResponse response) {
            if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
                return;
            }

            var usage = response.getMetadata().getUsage();
            if (usage.getPromptTokens() != null) {
                inputTokens += usage.getPromptTokens().intValue();
            }
            if (usage.getGenerationTokens() != null) {
                outputTokens += usage.getGenerationTokens().intValue();
            }
            if (usage.getTotalTokens() != null) {
                totalTokens += usage.getTotalTokens().intValue();
            } else {
                totalTokens = inputTokens + outputTokens;
            }
        }
    }
}
