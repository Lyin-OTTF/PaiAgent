package com.paiagent.engine.agent.hitl;

import com.alibaba.fastjson2.JSON;
import com.paiagent.engine.agent.policy.AutonomyLevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HITL 挂起上下文快照（迭代6）。
 *
 * <p>ReAct 节点发起需要人工审批的工具调用时，把「续跑所需的全部状态」序列化存入审批记录
 * （{@code approval_record.suspend_context}）。审批通过后 {@code HitlResumeService}
 * 凭此快照补执行工具并续跑 ReAct 循环——不重放已产生副作用的步骤，
 * 不依赖图级检查点，因此两个执行引擎（DAG / LangGraph）行为一致。
 *
 * <p>字段均为续跑最小充分集：
 * <ul>
 *   <li>身份类：executionId/workflowId/nodeId/tenantId/caller——回写执行记录与审计关联；</li>
 *   <li>任务类：goalPrompt（模板已渲染）/input——重建每步提示词；</li>
 *   <li>循环类：trace（含 pending 步骤）/step/maxSteps——从断点下一步继续；</li>
 *   <li>工具类：toolName/toolInput/allowedLevel——补执行被挂起的那一次调用。</li>
 * </ul>
 *
 * <p>已知边界（如实记录）：快照只覆盖 ReAct 节点内部循环；若该节点下游还有节点，
 * 它们已基于挂起时的 pending 输出执行过，补执行不会重放它们——
 * 图级「暂停-恢复」需要执行状态机重构，属独立迭代范围。
 */
public record HitlSuspendContext(
        Long executionId,
        Long workflowId,
        String nodeId,
        String tenantId,
        AutonomyLevel allowedLevel,
        String caller,
        String toolName,
        Map<String, Object> toolInput,
        String goalPrompt,
        Map<String, Object> input,
        List<Map<String, Object>> trace,
        int step,
        int maxSteps
) {

    /**
     * 序列化为上下文 Map（键名与记录组件一一对应，供 ToolGateway 落库）。
     * 单独抽出而非依赖 fastjson 对 record 的反射序列化：键序显式可控，
     * 且 {@link #roundTrip} 测试可完整验证「Map → JSON → record」不断链。
     */
    public Map<String, Object> toContextMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("executionId", executionId);
        map.put("workflowId", workflowId);
        map.put("nodeId", nodeId);
        map.put("tenantId", tenantId);
        map.put("allowedLevel", allowedLevel == null ? null : allowedLevel.name());
        map.put("caller", caller);
        map.put("toolName", toolName);
        map.put("toolInput", toolInput);
        map.put("goalPrompt", goalPrompt);
        map.put("input", input);
        map.put("trace", trace);
        map.put("step", step);
        map.put("maxSteps", maxSteps);
        return map;
    }

    /**
     * 从审批记录的 JSON 快照还原（HITL 恢复入口）。
     * 嵌套 Map/List 经 fastjson 反序列化为 JSONObject/JSONArray——均为 Map/List 实例，
     * 消费方（ReAct 续跑）按 Map 契约使用，与 JSON 往返后的状态表示一致。
     */
    public static HitlSuspendContext fromJson(String json) {
        return JSON.parseObject(json, HitlSuspendContext.class);
    }
}
