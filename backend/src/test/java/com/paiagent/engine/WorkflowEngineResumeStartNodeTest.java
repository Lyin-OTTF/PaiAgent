package com.paiagent.engine;

import com.paiagent.dto.ResumeExecutionRequest;
import com.paiagent.entity.ExecutionSnapshot;
import com.paiagent.engine.model.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 续跑起点解析的校验语义。
 *
 * <p>对应缺陷：{@code resolveResumeStartNodeId} 原实现把请求里的 startNodeId 直接透传，
 * 不校验它是否属于当前流程图。串行引擎按 id 找起点永远匹配不到，跳过全部节点后
 * 返回 SUCCESS（假成功）；并行引擎 startKnown=false 则从头全量重跑，重放副作用。
 * 因此这里锁定三条语义：合法起点原样返回、非法起点快速失败、未指定起点时按快照推导。
 */
class WorkflowEngineResumeStartNodeTest {

    private final WorkflowEngine engine = new WorkflowEngine();

    private static WorkflowNode node(String id) {
        WorkflowNode n = new WorkflowNode();
        n.setId(id);
        n.setType("llm");
        return n;
    }

    private static ExecutionSnapshot snapshot(String nodeId, String status) {
        ExecutionSnapshot s = new ExecutionSnapshot();
        s.setNodeId(nodeId);
        s.setStatus(status);
        return s;
    }

    private static ResumeExecutionRequest request(String startNodeId) {
        ResumeExecutionRequest r = new ResumeExecutionRequest();
        r.setStartNodeId(startNodeId);
        return r;
    }

    /** 起点存在于图中：原样返回，不被改写。 */
    @Test
    void resumeFromExistingNode_returnsRequestedId() {
        List<WorkflowNode> nodes = List.of(node("a"), node("b"), node("c"));
        String resolved = engine.resolveResumeStartNodeId(nodes, Map.of(), request("b"));
        assertEquals("b", resolved);
    }

    /**
     * 起点不存在于图中：必须抛 IllegalArgumentException，并且异常信息带上那个 id，
     * 便于调用方定位是哪个节点写错了。
     */
    @Test
    void resumeFromUnknownNode_failsFast() {
        List<WorkflowNode> nodes = List.of(node("a"), node("b"), node("c"));
        String ghost = "node-" + UUID.randomUUID();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> engine.resolveResumeStartNodeId(nodes, Map.of(), request(ghost)));
        assertTrue(ex.getMessage().contains(ghost),
                "异常信息应包含非法起点 id，实际: " + ex.getMessage());
    }

    /** 未指定起点：优先从 FAILED 快照恢复，便于失败重试直接打在断点上。 */
    @Test
    void resumeWithoutExplicitStart_prefersFailedSnapshot() {
        List<WorkflowNode> nodes = List.of(node("a"), node("b"), node("c"));
        Map<String, ExecutionSnapshot> snapshots = new LinkedHashMap<>();
        snapshots.put("a", snapshot("a", "SUCCESS"));
        snapshots.put("b", snapshot("b", "FAILED"));
        snapshots.put("c", snapshot("c", "SUCCESS"));

        String resolved = engine.resolveResumeStartNodeId(
                nodes, snapshots, request(null));
        assertEquals("b", resolved);
    }

    /** 全部节点都已成功：没有需要续跑的节点，返回 null 让调用方判定「已完成」。 */
    @Test
    void resumeWhenAllSucceeded_returnsNull() {
        List<WorkflowNode> nodes = List.of(node("a"), node("b"));
        Map<String, ExecutionSnapshot> snapshots = new LinkedHashMap<>();
        snapshots.put("a", snapshot("a", "SUCCESS"));
        snapshots.put("b", snapshot("b", "SKIPPED"));

        assertNull(engine.resolveResumeStartNodeId(nodes, snapshots, request("  ")));
    }
}
