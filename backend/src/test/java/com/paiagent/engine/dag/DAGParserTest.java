package com.paiagent.engine.dag;

import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.engine.model.WorkflowEdge;
import com.paiagent.engine.model.WorkflowNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迭代7（评审报告 A8）：DAG 解析的脏配置防御。
 *
 * <p>修复前「边引用了不存在的节点」会让 {@code dependencies.get(target).add(source)}
 * 直接 NPE——而可视化编辑器删除节点后残留连线是最常见的脏配置来源。
 */
class DAGParserTest {

    private final DAGParser parser = new DAGParser();

    @Test
    @DisplayName("悬空边被忽略而非 NPE（源或目标节点已不存在）")
    void ignoresDanglingEdges() {
        WorkflowConfig config = new WorkflowConfig();
        config.setNodes(new ArrayList<>(List.of(node("n1"), node("n2"))));
        config.setEdges(new ArrayList<>(List.of(
                edge("n1", "n2"),
                edge("n1", "ghost"),
                edge("ghost", "n2"))));

        List<WorkflowNode> sorted = assertDoesNotThrow(() -> parser.parse(config));

        assertEquals(2, sorted.size());
        assertEquals(List.of("n1", "n2"), sorted.stream().map(WorkflowNode::getId).toList());
    }

    @Test
    @DisplayName("nodes/edges 为 null 时按空集合处理，不抛异常")
    void toleratesNullCollections() {
        WorkflowConfig config = new WorkflowConfig();
        config.setNodes(null);
        config.setEdges(null);

        assertTrue(assertDoesNotThrow(() -> parser.parse(config)).isEmpty());
    }

    @Test
    @DisplayName("边的端点为 null 时跳过，不影响其余边")
    void skipsNullEndpoints() {
        WorkflowConfig config = new WorkflowConfig();
        config.setNodes(new ArrayList<>(List.of(node("n1"), node("n2"))));
        config.setEdges(new ArrayList<>(List.of(edge(null, "n2"), edge("n1", null), edge("n1", "n2"))));

        assertEquals(2, assertDoesNotThrow(() -> parser.parse(config)).size());
    }

    @Test
    @DisplayName("环检测仍然生效（容错不掩盖真正的循环依赖）")
    void stillDetectsCycle() {
        WorkflowConfig config = new WorkflowConfig();
        config.setNodes(new ArrayList<>(List.of(node("a"), node("b"))));
        config.setEdges(new ArrayList<>(List.of(edge("a", "b"), edge("b", "a"))));

        assertThrows(RuntimeException.class, () -> parser.parse(config));
    }

    @Test
    @DisplayName("正常线性图按拓扑序返回")
    void sortsLinearly() {
        WorkflowConfig config = new WorkflowConfig();
        config.setNodes(new ArrayList<>(List.of(node("c"), node("a"), node("b"))));
        config.setEdges(new ArrayList<>(List.of(edge("a", "b"), edge("b", "c"))));

        assertEquals(List.of("a", "b", "c"),
                parser.parse(config).stream().map(WorkflowNode::getId).toList());
    }

    private static WorkflowNode node(String id) {
        WorkflowNode node = new WorkflowNode();
        node.setId(id);
        node.setType("llm");
        return node;
    }

    private static WorkflowEdge edge(String source, String target) {
        WorkflowEdge edge = new WorkflowEdge();
        edge.setId(String.valueOf(source) + "->" + String.valueOf(target));
        edge.setSource(source);
        edge.setTarget(target);
        return edge;
    }
}
