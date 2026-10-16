package com.paiagent.engine;

import com.alibaba.fastjson2.JSON;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.engine.dag.DAGParser;
import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.executor.NodeExecutorFactory;
import com.paiagent.engine.executor.impl.ConditionNodeExecutor;
import com.paiagent.engine.executor.impl.InputNodeExecutor;
import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.engine.model.WorkflowEdge;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.ExecutionRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 测试 WorkflowEngine 的条件分支路由功能
 */
class ConditionBranchRoutingTest {

    private WorkflowEngine engine;
    private ExecutionRecordMapper executionRecordMapper;

    @BeforeEach
    void setUp() {
        engine = new WorkflowEngine();

        DAGParser dagParser = new DAGParser();
        ReflectionTestUtils.setField(engine, "dagParser", dagParser);
        ReflectionTestUtils.setField(engine, "workflowConfigParser", new WorkflowConfigParser());

        // 注册执行器：模拟 LLM 节点解析输入 JSON 字符串为 Map
        List<NodeExecutor> executors = List.of(
                new InputNodeExecutor(),
                new ConditionNodeExecutor(),
                new MockLlmNodeExecutor(),
                new SimpleOutputNodeExecutor("branchA"),
                new SimpleOutputNodeExecutor("branchB"),
                new SimpleOutputNodeExecutor("branchDefault")
        );
        NodeExecutorFactory factory = new NodeExecutorFactory(executors);
        ReflectionTestUtils.setField(engine, "executorFactory", factory);

        executionRecordMapper = mock(ExecutionRecordMapper.class);
        when(executionRecordMapper.insert(any(ExecutionRecord.class))).thenReturn(1);
        ReflectionTestUtils.setField(engine, "executionRecordMapper", executionRecordMapper);
    }

    @Test
    void testConditionBranchSelectsFirstBranch() {
        // 工作流: input -> mockLlm -> condition -> branchA / branchB
        // 条件: status == "success" 走 branchA
        Workflow workflow = buildConditionalWorkflow();

        ExecutionResponse response = engine.execute(workflow, "{\"status\":\"success\"}");

        assertEquals("SUCCESS", response.getStatus());

        // branchA 应该被执行
        boolean branchAExecuted = response.getNodeResults().stream()
                .anyMatch(r -> "branchA".equals(r.getNodeName()));
        assertTrue(branchAExecuted, "branchA should be executed");

        // branchB 应该被跳过
        boolean branchBExecuted = response.getNodeResults().stream()
                .anyMatch(r -> "branchB".equals(r.getNodeName()));
        assertFalse(branchBExecuted, "branchB should be skipped");
    }

    @Test
    void testConditionBranchSelectsSecondBranch() {
        Workflow workflow = buildConditionalWorkflow();

        ExecutionResponse response = engine.execute(workflow, "{\"status\":\"failed\"}");

        assertEquals("SUCCESS", response.getStatus());

        // branchB 应该被执行
        boolean branchBExecuted = response.getNodeResults().stream()
                .anyMatch(r -> "branchB".equals(r.getNodeName()));
        assertTrue(branchBExecuted, "branchB should be executed");

        // branchA 应该被跳过
        boolean branchAExecuted = response.getNodeResults().stream()
                .anyMatch(r -> "branchA".equals(r.getNodeName()));
        assertFalse(branchAExecuted, "branchA should be skipped");
    }

    @Test
    void testConditionBranchDefaultWhenNoMatch() {
        Workflow workflow = buildConditionalWorkflowWithDefault();

        ExecutionResponse response = engine.execute(workflow, "{\"status\":\"unknown\"}");

        assertEquals("SUCCESS", response.getStatus());

        // branchDefault 应该被执行（default 分支）
        boolean defaultExecuted = response.getNodeResults().stream()
                .anyMatch(r -> "branchDefault".equals(r.getNodeName()));
        assertTrue(defaultExecuted, "default branch should be executed");

        // branchA 和 branchB 应该被跳过
        boolean branchAExecuted = response.getNodeResults().stream()
                .anyMatch(r -> "branchA".equals(r.getNodeName()));
        assertFalse(branchAExecuted, "branchA should be skipped");
    }

    @Test
    void testLinearWorkflowUnaffected() {
        // 不包含条件节点的线性工作流应该不受影响
        Workflow workflow = buildLinearWorkflow();

        ExecutionResponse response = engine.execute(workflow, "hello");

        assertEquals("SUCCESS", response.getStatus());
        assertEquals(2, response.getNodeResults().size());
    }

    /**
     * 迭代7（双引擎对齐 · 多源）：多个无入边的源节点并存时，后续源节点必须拿到
     * <b>原始输入</b>，而不是「前一个源节点的输出」。
     * 修复前串行路径把可变的 currentInput 当兜底输入，导致源节点之间发生串味。
     */
    @Test
    void multiSourceNodesEachReceiveInitialInput() {
        registerExecutors(new SimpleOutputNodeExecutor("srcA"), new SimpleOutputNodeExecutor("srcB"));

        List<WorkflowNode> nodes = new ArrayList<>();
        nodes.add(createNode("node-srcA", "srcA", null));
        nodes.add(createNode("node-srcB", "srcB", null));

        WorkflowConfig config = new WorkflowConfig();
        config.setNodes(nodes);
        config.setEdges(new ArrayList<>());

        Workflow workflow = new Workflow();
        workflow.setId(10L);
        workflow.setFlowData(JSON.toJSONString(config));

        ExecutionResponse response = engine.execute(workflow, "seed");

        assertEquals("SUCCESS", response.getStatus());
        ExecutionResponse.NodeResult srcB = byNodeId(response, "node-srcB");
        assertNotNull(srcB, "源 B 应被执行");
        assertFalse(srcB.getInput().contains("executedBy"),
                "源 B 的输入不应携带源 A 的输出标记（修复前会把上一个源节点的输出串给它）");
        assertTrue(srcB.getInput().contains("\"input\":\"seed\""), "源 B 应拿到原始输入");
    }

    /**
     * 迭代7（双引擎对齐 · 条件空分支）：条件节点未回传 {@code __selectedBranch__} 时
     * 应按 default 分支处理，与 LangGraph 的 {@code resolveBranch} 语义一致。
     * 修复前 null 会让每条出边都落入 inactive（全部下游被跳过），两引擎行为相反。
     */
    @Test
    void conditionWithoutBranchFallsBackToDefault() {
        registerExecutors(new NoBranchConditionExecutor(), new SimpleOutputNodeExecutor("condDefault"));

        List<WorkflowNode> nodes = new ArrayList<>();
        nodes.add(createNode("node-input", "input", null));
        nodes.add(createNode("node-condition", "condition", null));
        nodes.add(createNode("node-default", "condDefault", null));
        nodes.add(createNode("node-branchA", "branchA", null));

        List<WorkflowEdge> edges = new ArrayList<>();
        edges.add(createEdge("e1", "node-input", "node-condition", null, null));
        // default 分支：出边不带 sourceHandle
        edges.add(createEdge("e2", "node-condition", "node-default", null, null));
        // 具名分支：明确未被选中
        edges.add(createEdge("e3", "node-condition", "node-branchA", "condition_0", null));

        WorkflowConfig config = new WorkflowConfig();
        config.setNodes(nodes);
        config.setEdges(edges);

        Workflow workflow = new Workflow();
        workflow.setId(11L);
        workflow.setFlowData(JSON.toJSONString(config));

        ExecutionResponse response = engine.execute(workflow, "seed");

        assertEquals("SUCCESS", response.getStatus());
        assertNotNull(byNodeId(response, "node-default"),
                "缺少分支信息时应回落到 default（修复前所有下游都会被跳过）");
        assertNull(byNodeId(response, "node-branchA"), "具名分支未被选中，仍应跳过");
    }

    // --- Helpers ---

    /** 用基础执行器集合 + 额外执行器重建工厂（同名类型以后者为准）。 */
    private void registerExecutors(NodeExecutor... extra) {
        List<NodeExecutor> executors = new ArrayList<>(List.of(
                new InputNodeExecutor(),
                new ConditionNodeExecutor(),
                new MockLlmNodeExecutor(),
                new SimpleOutputNodeExecutor("branchA"),
                new SimpleOutputNodeExecutor("branchB"),
                new SimpleOutputNodeExecutor("branchDefault")));
        executors.addAll(List.of(extra));
        ReflectionTestUtils.setField(engine, "executorFactory", new NodeExecutorFactory(executors));
    }

    private ExecutionResponse.NodeResult byNodeId(ExecutionResponse response, String nodeId) {
        return response.getNodeResults().stream()
                .filter(result -> nodeId.equals(result.getNodeId()))
                .findFirst()
                .orElse(null);
    }

    // --- Workflow Builders ---

    private Workflow buildConditionalWorkflow() {
        List<WorkflowNode> nodes = new ArrayList<>();
        List<WorkflowEdge> edges = new ArrayList<>();

        // Input node
        WorkflowNode inputNode = createNode("node-input", "input", null);
        nodes.add(inputNode);

        // Mock LLM node: 解析 input JSON 字符串为结构化 map
        WorkflowNode llmNode = createNode("node-llm", "mockLlm", null);
        nodes.add(llmNode);

        // Condition node
        List<Map<String, Object>> conditions = List.of(
                Map.of("id", "condition_0", "field", "status", "operator", "eq", "value", "success"),
                Map.of("id", "condition_1", "field", "status", "operator", "eq", "value", "failed")
        );
        WorkflowNode condNode = createNode("node-condition", "condition",
                Map.of("conditions", conditions));
        nodes.add(condNode);

        // Branch A node
        WorkflowNode branchANode = createNode("node-branchA", "branchA", null);
        nodes.add(branchANode);

        // Branch B node
        WorkflowNode branchBNode = createNode("node-branchB", "branchB", null);
        nodes.add(branchBNode);

        // Edges: input -> llm -> condition -> branchA/branchB
        edges.add(createEdge("e0", "node-input", "node-llm", null, null));
        edges.add(createEdge("e1", "node-llm", "node-condition", null, null));
        edges.add(createEdge("e2", "node-condition", "node-branchA", "condition_0", null));
        edges.add(createEdge("e3", "node-condition", "node-branchB", "condition_1", null));

        WorkflowConfig config = new WorkflowConfig();
        config.setNodes(nodes);
        config.setEdges(edges);

        Workflow workflow = new Workflow();
        workflow.setId(1L);
        workflow.setFlowData(JSON.toJSONString(config));
        return workflow;
    }

    private Workflow buildConditionalWorkflowWithDefault() {
        List<WorkflowNode> nodes = new ArrayList<>();
        List<WorkflowEdge> edges = new ArrayList<>();

        WorkflowNode inputNode = createNode("node-input", "input", null);
        nodes.add(inputNode);

        WorkflowNode llmNode = createNode("node-llm", "mockLlm", null);
        nodes.add(llmNode);

        List<Map<String, Object>> conditions = List.of(
                Map.of("id", "condition_0", "field", "status", "operator", "eq", "value", "success"),
                Map.of("id", "condition_1", "field", "status", "operator", "eq", "value", "failed")
        );
        WorkflowNode condNode = createNode("node-condition", "condition",
                Map.of("conditions", conditions));
        nodes.add(condNode);

        WorkflowNode branchANode = createNode("node-branchA", "branchA", null);
        nodes.add(branchANode);

        WorkflowNode branchBNode = createNode("node-branchB", "branchB", null);
        nodes.add(branchBNode);

        WorkflowNode branchDefaultNode = createNode("node-branchDefault", "branchDefault", null);
        nodes.add(branchDefaultNode);

        edges.add(createEdge("e0", "node-input", "node-llm", null, null));
        edges.add(createEdge("e1", "node-llm", "node-condition", null, null));
        edges.add(createEdge("e2", "node-condition", "node-branchA", "condition_0", null));
        edges.add(createEdge("e3", "node-condition", "node-branchB", "condition_1", null));
        edges.add(createEdge("e4", "node-condition", "node-branchDefault", "default", null));

        WorkflowConfig config = new WorkflowConfig();
        config.setNodes(nodes);
        config.setEdges(edges);

        Workflow workflow = new Workflow();
        workflow.setId(2L);
        workflow.setFlowData(JSON.toJSONString(config));
        return workflow;
    }

    private Workflow buildLinearWorkflow() {
        List<WorkflowNode> nodes = new ArrayList<>();
        List<WorkflowEdge> edges = new ArrayList<>();

        WorkflowNode inputNode = createNode("node-input", "input", null);
        nodes.add(inputNode);

        WorkflowNode outputNode = createNode("node-branchA", "branchA", null);
        nodes.add(outputNode);

        edges.add(createEdge("e1", "node-input", "node-branchA", null, null));

        WorkflowConfig config = new WorkflowConfig();
        config.setNodes(nodes);
        config.setEdges(edges);

        Workflow workflow = new Workflow();
        workflow.setId(3L);
        workflow.setFlowData(JSON.toJSONString(config));
        return workflow;
    }

    private WorkflowNode createNode(String id, String type, Map<String, Object> data) {
        WorkflowNode node = new WorkflowNode();
        node.setId(id);
        node.setType(type);
        node.setData(data != null ? new HashMap<>(data) : new HashMap<>());
        return node;
    }

    private WorkflowEdge createEdge(String id, String source, String target,
                                     String sourceHandle, String targetHandle) {
        WorkflowEdge edge = new WorkflowEdge();
        edge.setId(id);
        edge.setSource(source);
        edge.setTarget(target);
        edge.setSourceHandle(sourceHandle);
        edge.setTargetHandle(targetHandle);
        return edge;
    }

    /**
     * 模拟 LLM 节点：解析 input 字段中的 JSON 字符串为结构化 Map
     */
    static class MockLlmNodeExecutor implements NodeExecutor {
        @Override
        public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) {
            Object inputValue = input.get("input");
            if (inputValue instanceof String) {
                try {
                    Map<String, Object> parsed = JSON.parseObject((String) inputValue, Map.class);
                    return new HashMap<>(parsed);
                } catch (Exception e) {
                    // 非 JSON 字符串，直接返回
                }
            }
            return new HashMap<>(input);
        }

        @Override
        public String getSupportedNodeType() {
            return "mockLlm";
        }
    }

    /**
     * 模拟「条件节点未回传分支」的异常情形（迭代7 空分支容错的测试替身）。
     * 类型仍为 {@code condition}，因此会覆盖默认的 ConditionNodeExecutor。
     */
    static class NoBranchConditionExecutor implements NodeExecutor {
        @Override
        public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) {
            // 故意不写入 __selectedBranch__
            return new HashMap<>(input);
        }

        @Override
        public String getSupportedNodeType() {
            return "condition";
        }
    }

    /**
     * 简单的模拟节点执行器，只透传输入并添加标记
     */
    static class SimpleOutputNodeExecutor implements NodeExecutor {
        private final String nodeType;

        SimpleOutputNodeExecutor(String nodeType) {
            this.nodeType = nodeType;
        }

        @Override
        public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) {
            Map<String, Object> output = new HashMap<>(input);
            output.put("executedBy", nodeType);
            return output;
        }

        @Override
        public String getSupportedNodeType() {
            return nodeType;
        }
    }
}
