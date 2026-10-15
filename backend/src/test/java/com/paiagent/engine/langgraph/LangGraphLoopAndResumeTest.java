package com.paiagent.engine.langgraph;

import com.alibaba.fastjson2.JSON;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.dto.ResumeExecutionRequest;
import com.paiagent.engine.langgraph.checkpoint.LangGraphCheckpoint;
import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.engine.model.WorkflowEdge;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.ExecutionRecordMapper;
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
 * LangGraph 循环守卫 + 断点恢复端到端测试（迭代5）。
 *
 * <p>覆盖两类能力：
 * <ul>
 *   <li><b>LoopNode</b>：成环的工作流能正常构建执行，收敛时跑完退出，
 *       不收敛时在 maxIterations 处被强制终止并标记 FAILED；</li>
 *   <li><b>Checkpoint 恢复</b>：每次执行以 executionId 为线程落下检查点，
 *       {@code resumeExecution} 能从检查点继续，且状态经 MySQL JSON 往返后类型不丢。</li>
 * </ul>
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
class LangGraphLoopAndResumeTest {

    @Autowired
    private LangGraphWorkflowEngine langGraphEngine;

    @Autowired
    private ExecutionRecordMapper executionRecordMapper;

    // ------------------------------------------------------------------
    // LoopNode
    // ------------------------------------------------------------------

    @Test
    @DisplayName("自环工作流在第 2 轮收敛并正常退出")
    void shouldTerminateConvergingLoop() {
        // cond-1 的条件：__selectedBranch__ == "default" 时退出。
        // 第 1 轮该字段不存在 -> 走 default 回到自身；第 2 轮已存在 -> 退出。
        Workflow workflow = loopWorkflow(11L, "__selectedBranch__", "eq", "default", 5);

        ExecutionResponse response = langGraphEngine.execute(workflow, "{\"mode\":\"go\"}");

        assertEquals("SUCCESS", response.getStatus());
        List<String> executed = executedNodeIds(response);
        assertTrue(executed.contains("input-1"));
        assertTrue(executed.contains("cond-1"));
        assertTrue(executed.contains("out-done"));
        assertTrue(String.valueOf(response.getOutputData()).contains("LOOP_DONE"));
    }

    @Test
    @DisplayName("不收敛的自环在 maxIterations 处被强制终止")
    void shouldAbortNonConvergingLoop() {
        // 条件字段永远不存在 -> 永远走 default -> 无限自环
        Workflow workflow = loopWorkflow(12L, "__never_exists__", "eq", "x", 3);

        ExecutionResponse response = langGraphEngine.execute(workflow, "{\"mode\":\"go\"}");

        assertEquals("FAILED", response.getStatus());
        assertNotNull(response.getErrorMessage());
        assertTrue(response.getErrorMessage().contains("最大迭代次数"),
                "错误信息应体现循环超限，实际: " + response.getErrorMessage());
        assertTrue(response.getErrorMessage().contains("cond-1"),
                "错误信息应指出超限节点，实际: " + response.getErrorMessage());
    }

    // ------------------------------------------------------------------
    // Checkpoint / resumeExecution
    // ------------------------------------------------------------------

    @Test
    @DisplayName("执行后按 executionId 落下检查点，且已到达终点")
    void shouldPersistCheckpointAfterExecution() {
        Workflow workflow = loopWorkflow(13L, "__selectedBranch__", "eq", "default", 5);

        ExecutionResponse response = langGraphEngine.execute(workflow, "{\"mode\":\"go\"}");
        assertEquals("SUCCESS", response.getStatus());

        LangGraphCheckpoint latest = langGraphEngine.findLatestCheckpoint(response.getExecutionId());
        assertNotNull(latest, "执行结束后应留下检查点");
        assertEquals(String.valueOf(response.getExecutionId()), latest.getThreadId());
        assertEquals("__END__", latest.getNextNodeId(), "正常跑完的图末个检查点应指向 END");
        assertNotNull(latest.getStateJson());
    }

    @Test
    @DisplayName("断点恢复：状态经 MySQL JSON 往返后节点结果与输出不丢")
    void shouldResumeFromCheckpoint() {
        Workflow workflow = loopWorkflow(14L, "__selectedBranch__", "eq", "default", 5);

        ExecutionResponse original = langGraphEngine.execute(workflow, "{\"mode\":\"go\"}");
        assertEquals("SUCCESS", original.getStatus());

        ExecutionResponse resumed = langGraphEngine.resumeExecution(
                workflow, original.getExecutionId(), new ResumeExecutionRequest(), null);

        assertNotNull(resumed);
        assertEquals(original.getExecutionId(), resumed.getExecutionId(), "续跑应更新同一条执行记录");
        assertEquals("SUCCESS", resumed.getStatus());
        // nodeOutputs 走 JSON 往返，能取回即证明类型归一正确
        assertEquals(executedNodeIds(original).size(), executedNodeIds(resumed).size());
        assertTrue(executedNodeIds(resumed).contains("out-done"));
        assertTrue(String.valueOf(resumed.getOutputData()).contains("LOOP_DONE"));
    }

    @Test
    @DisplayName("断点恢复：执行记录不存在时报错")
    void shouldRejectResumeForUnknownExecution() {
        Workflow workflow = loopWorkflow(15L, "__selectedBranch__", "eq", "default", 5);
        assertThrows(IllegalArgumentException.class,
                () -> langGraphEngine.resumeExecution(workflow, 999999999L, new ResumeExecutionRequest(), null));
    }

    @Test
    @DisplayName("断点恢复：没有检查点时给出可读错误")
    void shouldRejectResumeWithoutCheckpoint() {
        Workflow workflow = loopWorkflow(16L, "__selectedBranch__", "eq", "default", 5);

        // 直接插一条属于该工作流、但从未执行过的执行记录 -> 记录存在但无检查点
        com.paiagent.entity.ExecutionRecord orphan = new com.paiagent.entity.ExecutionRecord();
        orphan.setFlowId(workflow.getId());
        orphan.setInputData(JSON.toJSONString(Map.of("input", "{\"mode\":\"go\"}")));
        orphan.setStatus("RUNNING");
        orphan.setNodeResults("[]");
        orphan.setDuration(0);
        executionRecordMapper.insert(orphan);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> langGraphEngine.resumeExecution(workflow, orphan.getId(), new ResumeExecutionRequest(), null));
        assertTrue(ex.getMessage().contains("检查点"), "错误信息应说明缺少检查点，实际: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private List<String> executedNodeIds(ExecutionResponse response) {
        List<String> ids = new ArrayList<>();
        for (ExecutionResponse.NodeResult nodeResult : response.getNodeResults()) {
            ids.add(nodeResult.getNodeId());
        }
        return ids;
    }

    /**
     * 构造自环工作流：
     * <pre>
     * input-1 -&gt; cond-1 --(exit)--&gt; out-done
     *                 \----(default)--&gt; cond-1 （回边，构成环）
     * </pre>
     *
     * @param exitField    退出条件字段
     * @param exitOperator 退出条件操作符
     * @param exitValue    退出条件期望值
     * @param maxIterations cond-1 的最大迭代次数
     */
    private Workflow loopWorkflow(Long id, String exitField, String exitOperator,
                                  Object exitValue, int maxIterations) {
        Workflow workflow = new Workflow();
        workflow.setId(id);
        workflow.setName("循环测试工作流-" + id);
        workflow.setEngineType("langgraph");

        List<WorkflowNode> nodes = new ArrayList<>();

        WorkflowNode inputNode = new WorkflowNode();
        inputNode.setId("input-1");
        inputNode.setType("input");
        inputNode.setPosition(new WorkflowNode.Position());
        inputNode.setData(Map.of("label", "输入", "type", "input"));
        nodes.add(inputNode);

        WorkflowNode conditionNode = new WorkflowNode();
        conditionNode.setId("cond-1");
        conditionNode.setType("condition");
        conditionNode.setPosition(new WorkflowNode.Position());
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("id", "exit");
        condition.put("field", exitField);
        condition.put("operator", exitOperator);
        condition.put("value", exitValue);
        Map<String, Object> conditionData = new HashMap<>();
        conditionData.put("label", "条件分支");
        conditionData.put("type", "condition");
        conditionData.put("conditions", List.of(condition));
        conditionData.put("maxIterations", maxIterations);
        conditionNode.setData(conditionData);
        nodes.add(conditionNode);

        WorkflowNode doneNode = new WorkflowNode();
        doneNode.setId("out-done");
        doneNode.setType("output");
        doneNode.setPosition(new WorkflowNode.Position());
        Map<String, Object> doneData = new HashMap<>();
        doneData.put("label", "输出");
        doneData.put("type", "output");
        doneData.put("outputParams", new ArrayList<>());
        doneData.put("responseContent", "LOOP_DONE");
        doneNode.setData(doneData);
        nodes.add(doneNode);

        List<WorkflowEdge> edges = new ArrayList<>();
        edges.add(edge("e1", "input-1", "cond-1", null));
        edges.add(edge("e2", "cond-1", "out-done", "exit"));
        edges.add(edge("e3", "cond-1", "cond-1", "default"));

        WorkflowConfig config = new WorkflowConfig();
        config.setNodes(nodes);
        config.setEdges(edges);
        workflow.setFlowData(JSON.toJSONString(config));
        return workflow;
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
