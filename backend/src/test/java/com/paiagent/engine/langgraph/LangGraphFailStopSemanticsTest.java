package com.paiagent.engine.langgraph;

import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.executor.NodeExecutorFactory;
import com.paiagent.engine.langgraph.adapter.NodeAdapter;
import com.paiagent.engine.langgraph.state.StateManager;
import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.engine.model.WorkflowNode;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 迭代6：LangGraph 引擎 fail-stop 失败语义 + 状态机三态完备化测试。
 *
 * <p>覆盖两类修复：
 * <ul>
 *   <li><b>失败语义对齐 DAG</b>：节点失败不再 fail-continue（写回失败状态后继续路由下游），
 *       而是抛出 {@link NodeExecutionException} 终止图执行，快照中保留已执行节点结果；</li>
 *   <li><b>状态机三态</b>：RUNNING（执行中）→ SUCCESS/FAILED（终态），
 *       {@code isSuccessful} 只认显式 SUCCESS，终态由 {@code markSuccess} 写入。</li>
 * </ul>
 */
class LangGraphFailStopSemanticsTest {

    private NodeExecutorFactory executorFactory;
    private NodeAdapter nodeAdapter;
    private StateManager stateManager;

    @BeforeEach
    void setUp() {
        executorFactory = mock(NodeExecutorFactory.class);
        nodeAdapter = new NodeAdapter();
        // 反射注入 mock 工厂（NodeAdapter 字段为 @Autowired private）
        org.springframework.test.util.ReflectionTestUtils.setField(nodeAdapter, "executorFactory", executorFactory);
        stateManager = new StateManager();
    }

    // ------------------------------------------------------------------
    // fail-stop 语义
    // ------------------------------------------------------------------

    @Test
    @DisplayName("节点失败：抛出携带快照的 NodeExecutionException，失败节点不进 nodeOutputs")
    void nodeFailureThrowsFailStopExceptionWithSnapshot() throws Exception {
        WorkflowNode node = node("fail-1", "input");
        NodeExecutor failing = mock(NodeExecutor.class);
        when(failing.execute(any(), any(), any())).thenThrow(new RuntimeException("boom"));
        when(executorFactory.getExecutor(eq("input"))).thenReturn(failing);

        Map<String, Object> initial = stateManager.initializeState("hello");
        initial.put("nodeStatuses", new LinkedHashMap<>(Map.of("ok-1", "SUCCESS")));
        Map<String, Map<String, Object>> outputs = new HashMap<>();
        outputs.put("ok-1", Map.of("result", "prior"));
        initial.put("nodeOutputs", outputs);

        AsyncNodeAction<AgentState> action = nodeAdapter.adaptNode(node, null);
        AgentState state = new AgentState(new HashMap<>(initial));

        NodeExecutionException ex = assertThrows(NodeExecutionException.class,
                () -> action.apply(state).join());

        // 异常信息可读（含节点 ID 与原因）
        assertTrue(ex.getMessage().contains("fail-1"));
        assertTrue(ex.getMessage().contains("boom"));

        Map<String, Object> snapshot = ex.getStateSnapshot();
        assertEquals("FAILED", snapshot.get("status"));
        assertTrue(String.valueOf(snapshot.get("errorMessage")).contains("fail-1"));

        // 失败节点记入 nodeStatuses=FAILED，但不进 nodeOutputs（没有有效输出）
        @SuppressWarnings("unchecked")
        Map<String, Object> statuses = (Map<String, Object>) snapshot.get("nodeStatuses");
        assertEquals("FAILED", statuses.get("fail-1"));
        assertEquals("SUCCESS", statuses.get("ok-1"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> snapshotOutputs =
                (Map<String, Map<String, Object>>) snapshot.get("nodeOutputs");
        assertFalse(snapshotOutputs.containsKey("fail-1"), "失败节点不应写入 nodeOutputs");
        assertTrue(snapshotOutputs.containsKey("ok-1"), "已成功节点的输出应保留在快照中");
    }

    @Test
    @DisplayName("节点失败：发送 NODE_ERROR 事件（与旧行为一致，不因改造丢失可观测性）")
    void nodeErrorEventEmittedOnFailure() throws Exception {
        WorkflowNode node = node("fail-2", "input");
        NodeExecutor failing = mock(NodeExecutor.class);
        when(failing.execute(any(), any(), any())).thenThrow(new IllegalStateException("bad state"));
        when(executorFactory.getExecutor(eq("input"))).thenReturn(failing);

        List<ExecutionEvent> events = new ArrayList<>();
        AsyncNodeAction<AgentState> action = nodeAdapter.adaptNode(node, events::add);
        AgentState state = new AgentState(new HashMap<>(stateManager.initializeState("x")));

        assertThrows(NodeExecutionException.class, () -> action.apply(state).join());
        assertTrue(events.stream().anyMatch(e -> "NODE_ERROR".equals(e.getEventType())),
                "失败应触发 NODE_ERROR 事件，实际: " + events);
    }

    @Test
    @DisplayName("节点成功：记录 nodeStatuses=SUCCESS，status 保持 RUNNING（终态由引擎出口写入）")
    void nodeSuccessRecordsStatusAndKeepsRunning() throws Exception {
        WorkflowNode node = node("ok-2", "input");
        NodeExecutor ok = mock(NodeExecutor.class);
        when(ok.execute(any(), any(), any())).thenReturn(Map.of("content", "done"));
        when(executorFactory.getExecutor(eq("input"))).thenReturn(ok);

        AsyncNodeAction<AgentState> action = nodeAdapter.adaptNode(node, null);
        AgentState state = new AgentState(new HashMap<>(stateManager.initializeState("x")));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) action.apply(state).join();

        assertEquals("RUNNING", result.get("status"), "图执行中不应提前置 SUCCESS");
        @SuppressWarnings("unchecked")
        Map<String, Object> statuses = (Map<String, Object>) result.get("nodeStatuses");
        assertEquals("SUCCESS", statuses.get("ok-2"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> outputs = (Map<String, Map<String, Object>>) result.get("nodeOutputs");
        assertEquals("done", outputs.get("ok-2").get("content"));
    }

    @Test
    @DisplayName("失败快照提取部分结果：已执行节点 SUCCESS、失败节点 FAILED、未启动节点缺席")
    void partialResultsFromFailedSnapshot() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("status", "FAILED");
        snapshot.put("errorMessage", "节点 mid 执行失败: boom");
        Map<String, Object> statuses = new LinkedHashMap<>();
        statuses.put("head", "SUCCESS");
        statuses.put("mid", "FAILED");
        snapshot.put("nodeStatuses", statuses);
        Map<String, Map<String, Object>> outputs = new HashMap<>();
        outputs.put("head", Map.of("content", "head-out"));
        snapshot.put("nodeOutputs", outputs);

        WorkflowConfig config = new WorkflowConfig();
        config.setNodes(List.of(node("head", "input"), node("mid", "openai"), node("tail", "output")));

        List<Map<String, Object>> results = stateManager.extractNodeResults(snapshot, config);

        assertEquals(2, results.size(), "未启动的 tail 不应出现在结果中");
        assertEquals("head", results.get(0).get("nodeId"));
        assertEquals("SUCCESS", results.get(0).get("status"));
        assertEquals("mid", results.get(1).get("nodeId"));
        assertEquals("FAILED", results.get(1).get("status"));
        assertNull(results.get(1).get("output"), "失败节点无输出");
        assertTrue(String.valueOf(results.get(1).get("error")).contains("mid"));
    }

    // ------------------------------------------------------------------
    // 状态机三态
    // ------------------------------------------------------------------

    @Test
    @DisplayName("isSuccessful 只认 SUCCESS：RUNNING/FAILED/缺省均判失败（旧实现把 RUNNING 算成功）")
    void isSuccessfulOnlyAcceptsSuccessTerminal() {
        assertFalse(stateManager.isSuccessful(Map.of("status", "RUNNING")),
                "RUNNING 是执行中状态，不应判成功");
        assertFalse(stateManager.isSuccessful(Map.of("status", "FAILED")));
        assertFalse(stateManager.isSuccessful(new HashMap<>()), "缺省状态不应判成功");
        assertTrue(stateManager.isSuccessful(Map.of("status", "SUCCESS")));

        Map<String, Object> state = stateManager.initializeState("x");
        assertFalse(stateManager.isSuccessful(state), "初始状态为 RUNNING，未跑完不应判成功");
        Map<String, Object> finalized = stateManager.markSuccess(state);
        assertTrue(stateManager.isSuccessful(finalized), "markSuccess 后应到达 SUCCESS 终态");
        assertFalse(stateManager.isSuccessful(state), "markSuccess 返回新 Map，不原地修改入参");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private WorkflowNode node(String id, String type) {
        WorkflowNode node = new WorkflowNode();
        node.setId(id);
        node.setType(type);
        node.setPosition(new WorkflowNode.Position());
        node.setData(Map.of("label", id, "type", type));
        return node;
    }
}
