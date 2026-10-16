package com.paiagent.engine;

import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.dag.DAGParser;
import com.alibaba.fastjson2.JSON;
import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.executor.NodeExecutorFactory;
import com.paiagent.engine.langgraph.adapter.NodeAdapter;
import com.paiagent.engine.langgraph.state.StateManager;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.ExecutionRecordMapper;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 迭代6：执行身份注入测试（HITL 恢复与审批审计的前置依赖）。
 *
 * <p>修复前节点输入从不携带 executionId/workflowId，导致：
 * ToolGateway 创建的审批记录 executionId 恒为 null（审计无法回溯执行）、
 * ActionLedger 无法关联执行、HITL 恢复没有锚点。本测试锁定两个引擎
 * （DAG 串行 / LangGraph）都会把执行身份投影进节点输入。
 */
class ExecutionIdentityInjectionTest {

    private static final Long EXECUTION_ID = 555L;
    private static final Long WORKFLOW_ID = 77L;

    private NodeExecutorFactory executorFactory;
    private final List<Map<String, Object>> capturedInputs = new CopyOnWriteArrayList<>();

    private static final String FLOW_DATA = """
            {"nodes":[
              {"id":"n1","type":"llm","data":{"type":"llm","provider":"openai","apiUrl":"u","apiKey":"k","model":"m","prompt":"p"}},
              {"id":"n2","type":"llm","data":{"type":"llm","provider":"openai","apiUrl":"u","apiKey":"k","model":"m","prompt":"p"}}
            ],
            "edges":[{"id":"e1","source":"n1","target":"n2"}]}
            """;

    @BeforeEach
    void setUp() throws Exception {
        capturedInputs.clear();
        executorFactory = mock(NodeExecutorFactory.class);
        NodeExecutor stub = mock(NodeExecutor.class);
        when(stub.execute(any(), any(), any())).thenAnswer(invocation -> {
            capturedInputs.add(invocation.getArgument(1));
            return new LinkedHashMap<>(Map.of("echo", "ok"));
        });
        when(executorFactory.getExecutor(anyString())).thenReturn(stub);
    }

    private Workflow workflow() {
        Workflow workflow = new Workflow();
        workflow.setId(WORKFLOW_ID);
        workflow.setFlowData(FLOW_DATA);
        return workflow;
    }

    @Test
    @DisplayName("DAG 引擎：节点输入携带 executionId/workflowId，并落进执行记录审计")
    void dagEngineInjectsExecutionIdentityIntoNodeInput() {
        WorkflowEngine engine = new WorkflowEngine();
        ExecutionRecordMapper recordMapper = mock(ExecutionRecordMapper.class);
        when(recordMapper.insert(any())).thenAnswer(invocation -> {
            ExecutionRecord record = invocation.getArgument(0);
            record.setId(EXECUTION_ID);
            return 1;
        });

        ReflectionTestUtils.setField(engine, "dagParser", new DAGParser());
        ReflectionTestUtils.setField(engine, "workflowConfigParser", new WorkflowConfigParser());
        ReflectionTestUtils.setField(engine, "executorFactory", executorFactory);
        ReflectionTestUtils.setField(engine, "executionRecordMapper", recordMapper);
        ReflectionTestUtils.setField(engine, "executionSnapshotMapper", null);
        ReflectionTestUtils.setField(engine, "executionVariableMapper", null);
        ReflectionTestUtils.setField(engine, "parallelScheduler", null);
        ReflectionTestUtils.setField(engine, "parallelEnabled", false);
        ReflectionTestUtils.setField(engine, "schedulerPoolSize", 4);

        engine.execute(workflow(), "seed");

        assertEquals(2, capturedInputs.size());
        for (Map<String, Object> input : capturedInputs) {
            assertEquals(EXECUTION_ID, ((Number) input.get("executionId")).longValue());
            assertEquals(WORKFLOW_ID, ((Number) input.get("workflowId")).longValue());
        }

        // 身份同时落进执行记录的 nodeResults（input 快照），审计可回溯。
        // 注意：NodeResult.input/output 是「JSON 字符串」字段，序列化到 nodeResults
        // 后内层引号会被转义，因此必须反序列化后再校验，不能直接对转义串做 contains。
        ArgumentCaptor<ExecutionRecord> captor = ArgumentCaptor.forClass(ExecutionRecord.class);
        verify(recordMapper, atLeastOnce()).updateById(captor.capture());
        ExecutionRecord persisted = captor.getValue();
        assertNotNull(persisted.getNodeResults());
        String firstInputJson = JSON.parseArray(persisted.getNodeResults())
                .getJSONObject(0).getString("input");
        assertNotNull(firstInputJson);
        Map<String, Object> persistedInput = JSON.parseObject(firstInputJson, Map.class);
        assertEquals(EXECUTION_ID, ((Number) persistedInput.get("executionId")).longValue());
        assertEquals(WORKFLOW_ID, ((Number) persistedInput.get("workflowId")).longValue());
    }

    @Test
    @DisplayName("LangGraph 引擎：每步从状态投影 executionId/workflowId 进节点输入")
    void langGraphInjectsExecutionIdentityIntoNodeInput() throws Exception {
        NodeAdapter adapter = new NodeAdapter();
        ReflectionTestUtils.setField(adapter, "executorFactory", executorFactory);

        Map<String, Object> state = new HashMap<>();
        state.put("currentInput", new HashMap<>(Map.of("input", "seed")));
        state.put("executionId", EXECUTION_ID);
        state.put("workflowId", WORKFLOW_ID);

        AsyncNodeAction<AgentState> action = adapter.adaptNode(node("n1"), null);
        action.apply(new AgentState(new HashMap<>(state))).join();

        assertEquals(1, capturedInputs.size());
        Map<String, Object> input = capturedInputs.get(0);
        assertEquals(EXECUTION_ID, ((Number) input.get("executionId")).longValue());
        assertEquals(WORKFLOW_ID, ((Number) input.get("workflowId")).longValue());
    }

    @Test
    @DisplayName("LangGraph 引擎：状态中无执行身份（老检查点恢复）时不注入、不报错")
    void langGraphToleratesMissingExecutionIdentity() throws Exception {
        NodeAdapter adapter = new NodeAdapter();
        ReflectionTestUtils.setField(adapter, "executorFactory", executorFactory);

        Map<String, Object> state = new HashMap<>();
        state.put("currentInput", new HashMap<>(Map.of("input", "seed")));

        AsyncNodeAction<AgentState> action = adapter.adaptNode(node("n1"), null);
        action.apply(new AgentState(new HashMap<>(state))).join();

        assertEquals(1, capturedInputs.size());
        assertFalse(capturedInputs.get(0).containsKey("executionId"));
        assertFalse(capturedInputs.get(0).containsKey("workflowId"));
    }

    private WorkflowNode node(String id) {
        WorkflowNode node = new WorkflowNode();
        node.setId(id);
        node.setType("llm");
        node.setData(new LinkedHashMap<>());
        return node;
    }
}
