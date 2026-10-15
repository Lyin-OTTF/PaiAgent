package com.paiagent.engine.langgraph;

import com.alibaba.fastjson2.JSON;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.engine.model.WorkflowEdge;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.entity.Workflow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LangGraph 条件边端到端测试（迭代5）。
 *
 * <p>验证条件节点（type=condition 且出边带 sourceHandle）在 LangGraph 引擎下
 * 只执行选中分支，未选中分支的节点完全不执行——这是改造前做不到的
 * （原来所有出边都会被无条件遍历）。
 */
@SpringBootTest(properties = {
        "paiagent.auth.jwt-secret=test-jwt-secret-key-for-spring-context-123456",
        "minio.endpoint=http://localhost:9000",
        "minio.accessKey=minioadmin",
        "minio.secretKey=minioadmin",
        "minio.bucketName=paiagent",
        "minio.publicUrl=http://localhost:9000",
        "spring.ai.openai.api-key=sk-test-placeholder",
        "spring.datasource.password=${MYSQL_PASSWORD:1234}"
})
class LangGraphConditionalEdgeTest {

    @Autowired
    private LangGraphWorkflowEngine langGraphEngine;

    @Test
    @DisplayName("条件命中时只走对应分支")
    void shouldRouteToMatchedBranchOnly() {
        Workflow workflow = branchingWorkflow(1L, "mode", "eq", "fast");

        ExecutionResponse response = langGraphEngine.execute(workflow, "{\"mode\":\"fast\"}");

        assertEquals("SUCCESS", response.getStatus());
        assertNotNull(response.getExecutionId());

        List<String> executed = executedNodeIds(response);
        assertTrue(executed.contains("input-1"), "入口节点应执行");
        assertTrue(executed.contains("cond-1"), "条件节点应执行");
        assertTrue(executed.contains("out-fast"), "命中分支节点应执行");
        assertFalse(executed.contains("out-slow"), "未命中分支节点不应执行");

        assertTrue(String.valueOf(response.getOutputData()).contains("FAST_PATH"));
    }

    @Test
    @DisplayName("条件不命中时走 default 分支")
    void shouldFallBackToDefaultBranch() {
        Workflow workflow = branchingWorkflow(2L, "mode", "eq", "fast");

        ExecutionResponse response = langGraphEngine.execute(workflow, "{\"mode\":\"slow\"}");

        assertEquals("SUCCESS", response.getStatus());

        List<String> executed = executedNodeIds(response);
        assertTrue(executed.contains("out-slow"), "default 分支节点应执行");
        assertFalse(executed.contains("out-fast"), "命中分支节点不应执行");

        assertTrue(String.valueOf(response.getOutputData()).contains("SLOW_PATH"));
    }

    @Test
    @DisplayName("同一份流程两次执行路由结果互不影响")
    void shouldRouteIndependentlyAcrossExecutions() {
        Workflow workflow = branchingWorkflow(3L, "mode", "eq", "fast");

        ExecutionResponse first = langGraphEngine.execute(workflow, "{\"mode\":\"fast\"}");
        ExecutionResponse second = langGraphEngine.execute(workflow, "{\"mode\":\"slow\"}");

        assertTrue(executedNodeIds(first).contains("out-fast"));
        assertTrue(executedNodeIds(second).contains("out-slow"));
        assertNotEquals(first.getExecutionId(), second.getExecutionId(),
                "每次执行都应有独立的执行记录（从而独立的检查点线程）");
    }

    @Test
    @DisplayName("事件回调按分支顺序触发")
    void shouldEmitEventsForExecutedBranch() {
        Workflow workflow = branchingWorkflow(4L, "mode", "eq", "fast");
        List<String> events = new ArrayList<>();

        ExecutionResponse response = langGraphEngine.executeWithCallback(
                workflow, "{\"mode\":\"fast\"}", event -> events.add(event.getEventType()));

        assertEquals("SUCCESS", response.getStatus());
        assertTrue(events.contains("WORKFLOW_START"));
        assertTrue(events.contains("NODE_START"));
        assertTrue(events.contains("NODE_SUCCESS"));
        assertTrue(events.contains("WORKFLOW_COMPLETE"));
    }

    // ------------------------------------------------------------------

    private List<String> executedNodeIds(ExecutionResponse response) {
        List<String> ids = new ArrayList<>();
        for (ExecutionResponse.NodeResult nodeResult : response.getNodeResults()) {
            ids.add(nodeResult.getNodeId());
        }
        return ids;
    }

    /**
     * 构造分支工作流：
     * <pre>
     * input-1 -&gt; cond-1 --(branch_fast)--&gt; out-fast
     *                \-----(default)-------&gt; out-slow
     * </pre>
     */
    private Workflow branchingWorkflow(Long id, String field, String operator, String value) {
        Workflow workflow = new Workflow();
        workflow.setId(id);
        workflow.setName("条件边测试工作流-" + id);
        workflow.setEngineType("langgraph");

        List<WorkflowNode> nodes = new ArrayList<>();

        WorkflowNode inputNode = simpleNode("input-1", "input");
        inputNode.setData(Map.of("label", "输入", "type", "input"));
        nodes.add(inputNode);

        WorkflowNode conditionNode = simpleNode("cond-1", "condition");
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("id", "branch_fast");
        condition.put("field", field);
        condition.put("operator", operator);
        condition.put("value", value);
        conditionNode.setData(Map.of("label", "条件分支", "type", "condition",
                "conditions", List.of(condition)));
        nodes.add(conditionNode);

        WorkflowNode fastNode = outputNode("out-fast", "FAST_PATH");
        nodes.add(fastNode);

        WorkflowNode slowNode = outputNode("out-slow", "SLOW_PATH");
        nodes.add(slowNode);

        List<WorkflowEdge> edges = new ArrayList<>();
        edges.add(edge("e1", "input-1", "cond-1", null));
        edges.add(edge("e2", "cond-1", "out-fast", "branch_fast"));
        edges.add(edge("e3", "cond-1", "out-slow", "default"));

        WorkflowConfig config = new WorkflowConfig();
        config.setNodes(nodes);
        config.setEdges(edges);
        workflow.setFlowData(JSON.toJSONString(config));
        return workflow;
    }

    private WorkflowNode simpleNode(String id, String type) {
        WorkflowNode node = new WorkflowNode();
        node.setId(id);
        node.setType(type);
        node.setPosition(new WorkflowNode.Position());
        return node;
    }

    private WorkflowNode outputNode(String id, String responseContent) {
        WorkflowNode node = simpleNode(id, "output");
        Map<String, Object> data = new HashMap<>();
        data.put("label", "输出");
        data.put("type", "output");
        data.put("outputParams", new ArrayList<>());
        data.put("responseContent", responseContent);
        node.setData(data);
        return node;
    }

    private WorkflowEdge edge(String id, String source, String target, String sourceHandle) {
        WorkflowEdge edge = new WorkflowEdge();
        edge.setId(id);
        edge.setSource(source);
        edge.setTarget(target);
        edge.setSourceHandle(sourceHandle);
        return edge;
    }
}
