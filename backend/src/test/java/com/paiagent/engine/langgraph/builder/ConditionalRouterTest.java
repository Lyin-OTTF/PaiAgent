package com.paiagent.engine.langgraph.builder;

import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.engine.model.WorkflowEdge;
import com.paiagent.engine.model.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConditionalRouter 单元测试（纯逻辑，无 Spring / 无 DB）。
 *
 * 覆盖三类契约：
 * 1. 条件节点识别（决定走条件边还是普通边）；
 * 2. 分支映射完整性（langgraph4j 要求运行期返回的 branchKey 必须能在映射中找到目标）；
 * 3. 运行期分支解析（与 ConditionNodeExecutor 写出的 __selectedBranch__ 对齐）。
 */
class ConditionalRouterTest {

    // ------------------------------------------------------------------
    // 1. 条件节点识别
    // ------------------------------------------------------------------

    @Test
    void shouldTreatConditionNodeWithHandlesAsConditional() {
        WorkflowNode node = conditionNode("cond-1");
        List<WorkflowEdge> edges = List.of(
                edge("cond-1", "out-a", "cond_a"),
                edge("cond-1", "out-b", "default")
        );
        assertTrue(ConditionalRouter.isConditionalNode(node, edges));
    }

    @Test
    void shouldNotTreatConditionNodeWithoutHandlesAsConditional() {
        // 历史遗留：条件节点出边没有 sourceHandle，保持走普通边（向后兼容）
        WorkflowNode node = conditionNode("cond-1");
        List<WorkflowEdge> edges = List.of(edge("cond-1", "out-a", null));
        assertFalse(ConditionalRouter.isConditionalNode(node, edges));
    }

    @Test
    void shouldNotTreatNonConditionNodeAsConditional() {
        WorkflowNode node = node("llm-1", "llm");
        List<WorkflowEdge> edges = List.of(edge("llm-1", "out-a", "some-handle"));
        assertFalse(ConditionalRouter.isConditionalNode(node, edges));
    }

    @Test
    void shouldNotTreatNodeWithoutOutgoingEdgesAsConditional() {
        WorkflowNode node = conditionNode("cond-1");
        assertFalse(ConditionalRouter.isConditionalNode(node, List.of()));
        assertFalse(ConditionalRouter.isConditionalNode(node, null));
    }

    // ------------------------------------------------------------------
    // 2. 分支映射
    // ------------------------------------------------------------------

    @Test
    void shouldMapEveryConditionIdPlusDefault() {
        WorkflowNode node = conditionNode("cond-1", "cond_a", "cond_b");
        List<WorkflowEdge> edges = List.of(
                edge("cond-1", "out-a", "cond_a"),
                edge("cond-1", "out-b", "default")
        );

        Map<String, String> mappings = ConditionalRouter.buildBranchMappings(node, edges);

        // 三个键都必须存在，否则运行期抛 missingNodeInEdgeMapping
        assertEquals(3, mappings.size());
        assertEquals("out-a", mappings.get("cond_a"));
        assertEquals("out-b", mappings.get("default"));
        assertTrue(mappings.containsKey("cond_b"));
    }

    @Test
    void shouldMapBranchWithoutEdgeToEnd() {
        WorkflowNode node = conditionNode("cond-1", "cond_a", "cond_b");
        List<WorkflowEdge> edges = List.of(edge("cond-1", "out-a", "cond_a"));

        Map<String, String> mappings = ConditionalRouter.buildBranchMappings(node, edges);

        // cond_b 没有对应出边 -> 收敛到 END，安全结束而不是抛映射缺失
        assertEquals("out-a", mappings.get("cond_a"));
        assertEquals("__END__", mappings.get("cond_b"));
        assertEquals("__END__", mappings.get("default"));
    }

    @Test
    void shouldTreatHandleLessEdgeAsDefaultBranch() {
        WorkflowNode node = conditionNode("cond-1", "cond_a");
        List<WorkflowEdge> edges = List.of(
                edge("cond-1", "out-a", "cond_a"),
                edge("cond-1", "out-default", null)
        );

        Map<String, String> mappings = ConditionalRouter.buildBranchMappings(node, edges);

        assertEquals("out-a", mappings.get("cond_a"));
        assertEquals("out-default", mappings.get("default"));
    }

    @Test
    void shouldKeepFirstTargetWhenBranchHasMultipleEdges() {
        WorkflowNode node = conditionNode("cond-1", "cond_a");
        List<WorkflowEdge> edges = List.of(
                edge("cond-1", "out-first", "cond_a"),
                edge("cond-1", "out-second", "cond_a")
        );

        Map<String, String> mappings = ConditionalRouter.buildBranchMappings(node, edges);

        // 已知限制：条件边只能返回单一目标，取画布上先连的一条
        assertEquals("out-first", mappings.get("cond_a"));
    }

    @Test
    void shouldAlwaysProduceNonEmptyMappingForNodeWithoutConditions() {
        WorkflowNode node = node("cond-1", "condition");
        Map<String, String> mappings = ConditionalRouter.buildBranchMappings(node, List.of());

        assertFalse(mappings.isEmpty());
        assertEquals("__END__", mappings.get(ConditionalRouter.DEFAULT_BRANCH));
    }

    // ------------------------------------------------------------------
    // 3. 运行期分支解析
    // ------------------------------------------------------------------

    @Test
    void shouldResolveBranchFromCurrentInput() {
        // ConditionNodeExecutor 把 __selectedBranch__ 写进节点输出，NodeAdapter 再放进 currentInput
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("currentInput", Map.of("__selectedBranch__", "cond_a", "mode", "fast"));

        assertEquals("cond_a", ConditionalRouter.resolveBranch(state));
    }

    @Test
    void shouldResolveBranchFromTopLevelWhenCurrentInputMissing() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("__selectedBranch__", "cond_b");

        assertEquals("cond_b", ConditionalRouter.resolveBranch(state));
    }

    @Test
    void shouldFallBackToDefaultWhenBranchMissingOrBlank() {
        assertEquals("default", ConditionalRouter.resolveBranch(null));
        assertEquals("default", ConditionalRouter.resolveBranch(Map.of()));

        Map<String, Object> blank = new LinkedHashMap<>();
        blank.put("currentInput", Map.of("__selectedBranch__", "   "));
        assertEquals("default", ConditionalRouter.resolveBranch(blank));
    }

    @Test
    void shouldIgnoreNonMapCurrentInput() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("currentInput", "not-a-map");
        state.put("__selectedBranch__", "cond_a");
        assertEquals("cond_a", ConditionalRouter.resolveBranch(state));
    }

    // ------------------------------------------------------------------
    // 4. 条件 id 提取
    // ------------------------------------------------------------------

    @Test
    void shouldExtractConditionIdsInDeclarationOrder() {
        WorkflowNode node = conditionNode("cond-1", "first", "second", "third");
        assertEquals(List.of("first", "second", "third"), ConditionalRouter.conditionIds(node));
    }

    @Test
    void shouldReturnEmptyConditionIdsWhenDataMissing() {
        assertTrue(ConditionalRouter.conditionIds(null).isEmpty());
        assertTrue(ConditionalRouter.conditionIds(node("x", "condition")).isEmpty());

        WorkflowNode bad = node("x", "condition");
        bad.setData(Map.of("conditions", "not-a-list"));
        assertTrue(ConditionalRouter.conditionIds(bad).isEmpty());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private WorkflowNode node(String id, String type) {
        WorkflowNode node = new WorkflowNode();
        node.setId(id);
        node.setType(type);
        node.setPosition(new WorkflowNode.Position());
        return node;
    }

    private WorkflowNode conditionNode(String id, String... conditionIds) {
        WorkflowNode node = node(id, "condition");
        List<Map<String, Object>> conditions = new ArrayList<>();
        for (String conditionId : conditionIds) {
            Map<String, Object> condition = new LinkedHashMap<>();
            condition.put("id", conditionId);
            condition.put("field", "mode");
            condition.put("operator", "eq");
            condition.put("value", "fast");
            conditions.add(condition);
        }
        node.setData(Map.of("conditions", conditions));
        return node;
    }

    private WorkflowEdge edge(String source, String target, String sourceHandle) {
        WorkflowEdge edge = new WorkflowEdge();
        edge.setId(source + "->" + target);
        edge.setSource(source);
        edge.setTarget(target);
        edge.setSourceHandle(sourceHandle);
        return edge;
    }
}
