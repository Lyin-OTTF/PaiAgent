package com.paiagent.engine.langgraph;

import com.alibaba.fastjson2.JSON;
import com.paiagent.dto.ExecutionEvent;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.dto.ResumeExecutionRequest;
import com.paiagent.engine.WorkflowConfigParser;
import com.paiagent.engine.WorkflowExecutor;
import com.paiagent.engine.langgraph.builder.GraphBuilder;
import com.paiagent.engine.langgraph.checkpoint.LangGraphCheckpoint;
import com.paiagent.engine.langgraph.checkpoint.LangGraphCheckpointSaver;
import com.paiagent.engine.langgraph.state.StateManager;
import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.ExecutionRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * LangGraph 工作流引擎（迭代5：threadId 绑定执行记录 + 断点续执行）。
 *
 * <p>关键约定：
 * <ul>
 *   <li><b>threadId = 执行记录 ID</b>：执行前先落一条 RUNNING 记录拿到主键，
 *       同一次执行产出的所有检查点归属同一线程；全新执行必然拿到新的 executionId，
 *       因此不会误接旧状态。</li>
 *   <li><b>断点续执行</b>：按 executionId 取该线程最新检查点，
 *       通过 {@code invoke(null, config)}（langgraph4j 的 resume 语义）从下一个待执行节点继续。</li>
 * </ul>
 */
@Slf4j
@Service
public class LangGraphWorkflowEngine implements WorkflowExecutor {

    @Autowired
    private GraphBuilder graphBuilder;

    @Autowired
    private StateManager stateManager;

    @Autowired
    private ExecutionRecordMapper executionRecordMapper;

    @Autowired
    private WorkflowConfigParser workflowConfigParser;

    /** 检查点持久化；缺省时为 null，此时断点续执行不可用。 */
    @Autowired(required = false)
    private LangGraphCheckpointSaver checkpointSaver;

    @Override
    public ExecutionResponse execute(Workflow workflow, String inputData) {
        return executeWithCallback(workflow, inputData, null);
    }

    @Override
    public ExecutionResponse executeWithCallback(
            Workflow workflow,
            String inputData,
            Consumer<ExecutionEvent> eventCallback) {

        long startTime = System.currentTimeMillis();
        String status = "SUCCESS";
        String errorMessage = null;
        ExecutionRecord record = null;

        log.info("开始执行工作流 [{}] - 使用 LangGraph 引擎", workflow.getName());

        try {
            // 1. 解析工作流配置
            WorkflowConfig config = workflowConfigParser.parse(workflow.getFlowData());
            log.info("工作流配置解析完成: 节点数={}, 边数={}",
                config.getNodes().size(), config.getEdges().size());

            // 2. 先落 RUNNING 记录，用执行记录 ID 作为检查点线程 ID
            record = createRunningRecord(workflow.getId(), inputData);
            if (eventCallback != null) {
                eventCallback.accept(ExecutionEvent.workflowStart(record.getId()));
            }

            // 3. 构建 LangGraph（条件边 / 循环守卫 / Checkpoint 装配）
            CompiledGraph<AgentState> compiledGraph = graphBuilder.buildGraph(config, eventCallback);

            // 4. 初始化状态（使用 AgentState）
            Map<String, Object> initialStateData = stateManager.initializeState(inputData);

            // 5. 执行图
            log.info("开始执行 LangGraph");
            RunnableConfig runConfig = RunnableConfig.builder()
                    .threadId(String.valueOf(record.getId()))
                    .build();
            var result = compiledGraph.invoke(initialStateData, runConfig);

            // 提取最终状态
            Map<String, Object> finalState;
            if (result.isPresent()) {
                finalState = result.get().data();
            } else {
                throw new RuntimeException("LangGraph 执行返回空结果");
            }

            log.info("LangGraph 执行完成");

            // 6. 检查执行状态
            if (!stateManager.isSuccessful(finalState)) {
                status = "FAILED";
                errorMessage = stateManager.getErrorMessage(finalState);
            }

            // 7. 提取输出数据
            Map<String, Object> outputData = stateManager.getFinalOutput(finalState);
            String outputDataJson = JSON.toJSONString(outputData);

            // 8. 提取节点执行结果
            var nodeResultsList = stateManager.extractNodeResults(finalState, config);

            // 9. 保存执行记录
            long endTime = System.currentTimeMillis();
            int duration = (int) (endTime - startTime);

            record.setOutputData(outputDataJson);
            record.setStatus(status);
            record.setNodeResults(JSON.toJSONString(nodeResultsList));
            record.setErrorMessage(errorMessage);
            record.setDuration(duration);
            executionRecordMapper.updateById(record);

            // 10. 触发工作流完成事件
            if (eventCallback != null) {
                eventCallback.accept(ExecutionEvent.workflowComplete(status, outputData, duration));
            }

            // 11. 构建响应
            return buildResponse(record, nodeResultsList, outputDataJson, duration);

        } catch (Exception e) {
            status = "FAILED";
            errorMessage = e.getMessage();

            log.error("工作流执行失败", e);

            // 触发错误事件
            if (eventCallback != null) {
                eventCallback.accept(ExecutionEvent.workflowComplete("FAILED", errorMessage,
                    (int) (System.currentTimeMillis() - startTime)));
            }

            // 保存失败记录
            long endTime = System.currentTimeMillis();
            int duration = (int) (endTime - startTime);

            if (record == null) {
                record = new ExecutionRecord();
                record.setFlowId(workflow.getId());
                record.setInputData(JSON.toJSONString(Map.of("input", inputData)));
                record.setNodeResults("[]");
                executionRecordMapper.insert(record);
            }
            record.setStatus("FAILED");
            record.setErrorMessage(errorMessage);
            record.setDuration(duration);
            executionRecordMapper.updateById(record);

            // 返回错误响应
            ExecutionResponse response = new ExecutionResponse();
            response.setExecutionId(record.getId());
            response.setStatus("FAILED");
            response.setInputData(record.getInputData());
            response.setNodeResults(new ArrayList<>());
            response.setOutputData(null);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }

    /**
     * 从检查点续执行工作流（迭代5：LangGraph 断点恢复）。
     *
     * <p>与 DAG 引擎的 {@code WorkflowEngine.resumeExecution} 对齐：同样接收
     * {@link ResumeExecutionRequest}，支持 {@code modifiedVariables} 改写状态、
     * {@code startNodeId} 指定从哪个节点之后继续。
     *
     * @throws IllegalStateException 未装配 CheckpointSaver / 找不到检查点 / 执行已到终点
     */
    public ExecutionResponse resumeExecution(Workflow workflow,
                                             Long executionId,
                                             ResumeExecutionRequest request,
                                             Consumer<ExecutionEvent> eventCallback) {
        if (checkpointSaver == null) {
            throw new IllegalStateException("LangGraph 断点续执行未启用: checkpointSaver 不可用");
        }

        ExecutionRecord record = executionRecordMapper.selectById(executionId);
        if (record == null || !Objects.equals(record.getFlowId(), workflow.getId())) {
            throw new IllegalArgumentException("执行记录不存在或不属于当前工作流");
        }

        ResumeExecutionRequest resumeRequest = request != null ? request : new ResumeExecutionRequest();
        WorkflowConfig config = workflowConfigParser.parse(workflow.getFlowData());
        String threadId = String.valueOf(executionId);

        LangGraphCheckpoint latest = checkpointSaver.findLatest(threadId);
        if (latest == null) {
            throw new IllegalStateException("未找到执行 [" + executionId + "] 的 LangGraph 检查点，无法续执行");
        }
        if (latest.getNextNodeId() == null || latest.getNextNodeId().isBlank()) {
            throw new IllegalStateException("该执行已无后续节点，无需续执行");
        }

        long startTime = System.currentTimeMillis();
        record.setStatus("RUNNING");
        record.setErrorMessage(null);
        executionRecordMapper.updateById(record);

        if (eventCallback != null) {
            eventCallback.accept(ExecutionEvent.workflowStart(record.getId()));
        }

        try {
            CompiledGraph<AgentState> compiledGraph = graphBuilder.buildGraph(config, eventCallback);
            RunnableConfig resumeConfig = RunnableConfig.builder().threadId(threadId).build();

            // 需要改写状态时，先把修改合并进检查点，并顺带指定下一步节点
            Map<String, Object> modified = resumeRequest.getModifiedVariables();
            if (modified != null && !modified.isEmpty()) {
                Map<String, Object> values = new LinkedHashMap<>(modified);
                resumeConfig = compiledGraph.updateState(resumeConfig, values, resumeRequest.getStartNodeId());
            } else {
                resumeConfig = resumeConfig.withCheckPointId(latest.getId());
            }

            // langgraph4j 的恢复语义：inputs 传 null 即「从检查点继续」
            var result = compiledGraph.invoke(null, resumeConfig);
            if (result.isEmpty()) {
                throw new RuntimeException("LangGraph 恢复执行返回空结果");
            }
            Map<String, Object> finalState = result.get().data();

            String status = "SUCCESS";
            String errorMessage = null;
            if (!stateManager.isSuccessful(finalState)) {
                status = "FAILED";
                errorMessage = stateManager.getErrorMessage(finalState);
            }

            Map<String, Object> outputData = stateManager.getFinalOutput(finalState);
            String outputDataJson = JSON.toJSONString(outputData);
            var nodeResultsList = stateManager.extractNodeResults(finalState, config);
            int duration = (int) (System.currentTimeMillis() - startTime);

            record.setOutputData(outputDataJson);
            record.setStatus(status);
            record.setNodeResults(JSON.toJSONString(nodeResultsList));
            record.setErrorMessage(errorMessage);
            record.setDuration(duration);
            executionRecordMapper.updateById(record);

            if (eventCallback != null) {
                eventCallback.accept(ExecutionEvent.workflowComplete(status, outputData, duration));
            }
            return buildResponse(record, nodeResultsList, outputDataJson, duration);

        } catch (Exception e) {
            log.error("LangGraph 断点续执行失败: executionId={}", executionId, e);
            record.setStatus("FAILED");
            record.setErrorMessage(e.getMessage());
            record.setDuration((int) (System.currentTimeMillis() - startTime));
            executionRecordMapper.updateById(record);

            if (eventCallback != null) {
                eventCallback.accept(ExecutionEvent.workflowComplete("FAILED", e.getMessage(),
                        (int) (System.currentTimeMillis() - startTime)));
            }
            // 保留 IllegalStateException / IllegalArgumentException 的既有语义
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("LangGraph 断点续执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询某次执行的 LangGraph 检查点（供 Controller 暴露续跑入口）。
     *
     * @return 无检查点时返回 {@code null}
     */
    public LangGraphCheckpoint findLatestCheckpoint(Long executionId) {
        if (checkpointSaver == null) {
            return null;
        }
        return checkpointSaver.findLatest(String.valueOf(executionId));
    }

    @Override
    public String getEngineType() {
        return "langgraph";
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    private ExecutionRecord createRunningRecord(Long flowId, String inputData) {
        ExecutionRecord record = new ExecutionRecord();
        record.setFlowId(flowId);
        record.setInputData(JSON.toJSONString(Map.of("input", inputData)));
        record.setStatus("RUNNING");
        record.setNodeResults("[]");
        record.setDuration(0);
        executionRecordMapper.insert(record);
        return record;
    }

    private ExecutionResponse buildResponse(ExecutionRecord record,
                                            java.util.List<Map<String, Object>> nodeResultsList,
                                            String outputDataJson,
                                            int duration) {
        ExecutionResponse response = new ExecutionResponse();
        response.setExecutionId(record.getId());
        response.setStatus(record.getStatus());
        response.setInputData(record.getInputData());

        java.util.List<ExecutionResponse.NodeResult> nodeResults = new ArrayList<>();
        for (Map<String, Object> nodeResultMap : nodeResultsList) {
            ExecutionResponse.NodeResult nodeResult = new ExecutionResponse.NodeResult();
            nodeResult.setNodeId((String) nodeResultMap.get("nodeId"));
            nodeResult.setNodeName((String) nodeResultMap.get("nodeName"));
            nodeResult.setStatus((String) nodeResultMap.get("status"));
            nodeResult.setOutput((String) nodeResultMap.get("output"));
            nodeResults.add(nodeResult);
        }
        response.setNodeResults(nodeResults);

        response.setOutputData(outputDataJson);
        response.setErrorMessage(record.getErrorMessage());
        response.setDuration(duration);
        return response;
    }
}
