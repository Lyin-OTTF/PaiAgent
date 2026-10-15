package com.paiagent.engine.langgraph.builder;

import com.paiagent.engine.model.WorkflowEdge;
import com.paiagent.engine.model.WorkflowNode;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.StateGraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 条件边路由决策器（迭代5：LangGraph 条件边补齐）。
 *
 * <p>LangGraph4j 的 {@code addConditionalEdges} 要求「一个源节点 + 一份 branchKey -&gt; targetNodeId 映射」，
 * 且运行期返回的 branchKey 必须能在映射中找到目标，否则抛 {@code missingNodeInEdgeMapping}。
 * 本类负责把 PaiAgent 的条件节点配置（{@code node.data.conditions}）与画布出边（{@code edge.sourceHandle}）
 * 编译成这份映射，并在运行期从图状态中解析当前应走的分支。
 *
 * <p><b>分支键（branchKey）来源</b>：
 * <ul>
 *   <li>每个条件的 {@code id}（与前端 {@code <Handle id={c.id}/>} 一一对应）；</li>
 *   <li>{@value #DEFAULT_BRANCH} —— 无条件匹配时的默认分支（前端默认 Handle 的 id 同为 "default"）。</li>
 * </ul>
 *
 * <p><b>与 DAG 引擎保持一致的路由语义</b>（参照 {@code WorkflowEngine.markSkippedNodes}）：
 * 出边 {@code sourceHandle} 等于选中分支时该边活跃；{@code sourceHandle} 为空且选中分支为 default 时该边活跃。
 *
 * <p>本类不依赖 Spring，可独立单测。
 */
@Slf4j
public final class ConditionalRouter {

    /** 默认分支键：无条件匹配时使用，亦对应前端默认 Handle 的 id。 */
    public static final String DEFAULT_BRANCH = "default";

    /** 条件节点执行器写入的分支字段名。 */
    public static final String BRANCH_KEY = "__selectedBranch__";

    /** 节点输出在状态中的键名（NodeAdapter 会把节点输出写入 currentInput）。 */
    private static final String CURRENT_INPUT_KEY = "currentInput";

    private ConditionalRouter() {
    }

    /**
     * 判断节点是否需要「条件边」表达。
     *
     * <p>仅当节点类型为 {@code condition} 且<b>存在带 sourceHandle 的出边</b>时为 true；
     * 其余情况（含历史遗留的、出边没有 sourceHandle 的条件节点）走普通边，保持向后兼容。
     */
    public static boolean isConditionalNode(WorkflowNode node, List<WorkflowEdge> outgoingEdges) {
        if (node == null || !"condition".equals(node.getType())) {
            return false;
        }
        return hasHandles(outgoingEdges);
    }

    /**
     * 计算 branchKey -&gt; targetNodeId 映射。
     *
     * <p>保证映射覆盖所有可能返回的 branchKey：每个条件 id 与 {@value #DEFAULT_BRANCH} 都会出现；
     * 某分支没有对应出边时映射到 {@link StateGraph#END}，从而安全地结束图而不是抛映射缺失异常。
     *
     * @param conditionNode 条件节点（读取 {@code data.conditions} 拿到全部条件 id）
     * @param outgoingEdges 该节点的全部出边
     * @return 映射永不为空（调用方需先通过 {@link #isConditionalNode} 判断）
     */
    public static Map<String, String> buildBranchMappings(WorkflowNode conditionNode, List<WorkflowEdge> outgoingEdges) {
        Map<String, String> mappings = new LinkedHashMap<>();
        if (conditionNode == null) {
            mappings.put(DEFAULT_BRANCH, StateGraph.END);
            return mappings;
        }

        // 1. 收集全部可能的分支键：条件 id + default
        Set<String> branchKeys = new LinkedHashSet<>();
        branchKeys.addAll(conditionIds(conditionNode));
        branchKeys.add(DEFAULT_BRANCH);

        // 2. 用出边的 sourceHandle 回填目标节点；无 handle 的出边视为 default 分支
        Map<String, String> byHandle = new LinkedHashMap<>();
        if (outgoingEdges != null) {
            for (WorkflowEdge edge : outgoingEdges) {
                String handle = edge.getSourceHandle();
                String branch = (handle == null || handle.isBlank()) ? DEFAULT_BRANCH : handle;
                String target = edge.getTarget();
                if (target == null || target.isBlank()) {
                    continue;
                }
                String existing = byHandle.putIfAbsent(branch, target);
                if (existing != null && !existing.equals(target)) {
                    // 同一分支键对应多个目标节点：LangGraph 条件边只能返回单一目标，
                    // 取第一条（画布上先连的）并记录告警，属于已知限制。
                    log.warn("条件节点 [{}] 分支 [{}] 存在多个目标节点（{} 与 {}），采用 [{}]",
                            conditionNode.getId(), branch, existing, target, existing);
                }
            }
        }

        // 3. 无条件边的分支映射到 END，保证任何返回值都有落脚点
        for (String branch : branchKeys) {
            mappings.put(branch, byHandle.getOrDefault(branch, StateGraph.END));
        }
        return mappings;
    }

    /**
     * 从图状态解析当前分支键。
     *
     * <p>查找顺序：{@code currentInput.__selectedBranch__}（条件节点执行器写入节点输出，
     * NodeAdapter 会把它放进 currentInput） -&gt; 状态顶层的 {@code __selectedBranch__} -&gt; {@value #DEFAULT_BRANCH}。
     */
    public static String resolveBranch(Map<String, Object> stateData) {
        if (stateData == null) {
            return DEFAULT_BRANCH;
        }
        Object currentInput = stateData.get(CURRENT_INPUT_KEY);
        if (currentInput instanceof Map<?, ?> input) {
            Object branch = input.get(BRANCH_KEY);
            if (branch != null && !String.valueOf(branch).isBlank()) {
                return String.valueOf(branch);
            }
        }
        Object direct = stateData.get(BRANCH_KEY);
        if (direct != null && !String.valueOf(direct).isBlank()) {
            return String.valueOf(direct);
        }
        return DEFAULT_BRANCH;
    }

    /** 提取条件节点的全部条件 id（顺序保持配置顺序）。 */
    public static List<String> conditionIds(WorkflowNode conditionNode) {
        List<String> ids = new ArrayList<>();
        if (conditionNode == null || conditionNode.getData() == null) {
            return ids;
        }
        Object raw = conditionNode.getData().get("conditions");
        if (!(raw instanceof List<?> conditions)) {
            return ids;
        }
        for (Object item : conditions) {
            if (!(item instanceof Map<?, ?> condition)) {
                continue;
            }
            Object id = condition.get("id");
            if (id != null && !String.valueOf(id).isBlank()) {
                ids.add(String.valueOf(id));
            }
        }
        return ids;
    }

    private static boolean hasHandles(List<WorkflowEdge> outgoingEdges) {
        if (outgoingEdges == null) {
            return false;
        }
        return outgoingEdges.stream().anyMatch(edge -> edge.getSourceHandle() != null);
    }
}
