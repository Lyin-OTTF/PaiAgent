package com.paiagent.controller;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.common.Result;
import com.paiagent.common.UserContext;
import com.paiagent.dto.ExecutionEvent;
import com.paiagent.dto.ExecutionRequest;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.dto.ExecutionSnapshotResponse;
import com.paiagent.dto.ExecutionVariableResponse;
import com.paiagent.dto.ResumeExecutionRequest;
import com.paiagent.engine.EngineSelector;
import com.paiagent.engine.WorkflowExecutor;
import com.paiagent.engine.WorkflowEngine;
import com.paiagent.engine.agent.tenant.TenantContext;
import com.paiagent.engine.langgraph.LangGraphWorkflowEngine;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.entity.ExecutionSnapshot;
import com.paiagent.entity.ExecutionVariable;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.ExecutionRecordMapper;
import com.paiagent.mapper.ExecutionSnapshotMapper;
import com.paiagent.mapper.ExecutionVariableMapper;
import com.paiagent.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Slf4j
@Tag(name = "工作流执行接口")
@RestController
@RequestMapping("/api/workflows")
public class ExecutionController {
    
    @Autowired
    private WorkflowService workflowService;
    
    @Autowired
    private EngineSelector engineSelector;

    @Autowired
    private ExecutionRecordMapper executionRecordMapper;

    @Autowired
    private ExecutionSnapshotMapper executionSnapshotMapper;

    @Autowired
    private ExecutionVariableMapper executionVariableMapper;
    
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * SSE 执行线程池（迭代7 · 中危修复）：替代原先「每请求一个裸线程」。
     * 有界线程数（上限 16）+ 有界队列（64）+ CallerRuns 兜底，避免高并发下线程耗尽；
     * 守护线程不阻塞 JVM 退出，固定线程名便于排查。
     */
    private final ExecutorService sseExecutor = new ThreadPoolExecutor(
            4, 16, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),
            new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger(1);

                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "sse-exec-" + seq.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    @PreDestroy
    public void shutdownSseExecutor() {
        sseExecutor.shutdown();
    }
    
    @Operation(summary = "执行工作流")
    @PostMapping("/{id}/execute")
    public Result<ExecutionResponse> executeWorkflow(@PathVariable Long id, @Valid @RequestBody ExecutionRequest request) {
        Workflow workflow;
        try {
            // 迭代7 A1：执行入口同样按归属校验，避免「列表看不到却能按 id 执行」
            workflow = workflowService.getOwnedWorkflow(id);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
        
        try {
            // 使用引擎选择器选择合适的执行引擎
            WorkflowExecutor executor = engineSelector.selectEngine(workflow);
            ExecutionResponse response = executor.execute(workflow, request.getInputData());
            return Result.success(response);
        } catch (Exception e) {
            return Result.error("工作流执行失败: " + e.getMessage());
        }
    }

    @Operation(summary = "获取工作流最近一次执行记录")
    @GetMapping("/{id}/executions/latest")
    public Result<ExecutionResponse> getLatestExecution(@PathVariable Long id) {
        try {
            // 迭代7 A1：最近执行记录同样受归属保护
            workflowService.getOwnedWorkflow(id);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }

        LambdaQueryWrapper<ExecutionRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ExecutionRecord::getFlowId, id)
               .orderByDesc(ExecutionRecord::getId)
               .last("LIMIT 1");

        ExecutionRecord record = executionRecordMapper.selectOne(wrapper);
        if (record == null) {
            return Result.success(null);
        }

        ExecutionResponse response = new ExecutionResponse();
        response.setExecutionId(record.getId());
        response.setStatus(record.getStatus());
        response.setInputData(record.getInputData());
        response.setOutputData(record.getOutputData());
        response.setDuration(record.getDuration());
        response.setErrorMessage(record.getErrorMessage());
        response.setNodeResults(parseNodeResults(record.getNodeResults()));
        return Result.success(response);
    }

    @Operation(summary = "获取执行节点快照")
    @GetMapping("/{id}/executions/{executionId}/snapshots")
    public Result<List<ExecutionSnapshotResponse>> getExecutionSnapshots(@PathVariable Long id,
                                                                         @PathVariable Long executionId) {
        ExecutionRecord record = executionRecordMapper.selectById(executionId);
        if (record == null || !id.equals(record.getFlowId())) {
            return Result.error("执行记录不存在");
        }

        List<ExecutionSnapshot> snapshots = executionSnapshotMapper.selectByExecutionId(executionId);
        List<ExecutionSnapshotResponse> responses = new ArrayList<>();
        for (ExecutionSnapshot snapshot : snapshots) {
            responses.add(toSnapshotResponse(snapshot));
        }
        return Result.success(responses);
    }

    @Operation(summary = "获取执行变量")
    @GetMapping("/{id}/executions/{executionId}/variables")
    public Result<List<ExecutionVariableResponse>> getExecutionVariables(@PathVariable Long id,
                                                                         @PathVariable Long executionId) {
        ExecutionRecord record = executionRecordMapper.selectById(executionId);
        if (record == null || !id.equals(record.getFlowId())) {
            return Result.error("执行记录不存在");
        }

        List<ExecutionVariable> variables = executionVariableMapper.selectByExecutionId(executionId);
        List<ExecutionVariableResponse> responses = new ArrayList<>();
        for (ExecutionVariable variable : variables) {
            responses.add(toVariableResponse(variable));
        }
        return Result.success(responses);
    }

    @Operation(summary = "从断点继续执行工作流")
    @PostMapping("/{id}/executions/{executionId}/resume")
    public Result<ExecutionResponse> resumeExecution(@PathVariable Long id,
                                                     @PathVariable Long executionId,
                                                     @RequestBody(required = false) ResumeExecutionRequest request) {
        Workflow workflow;
        try {
            // 迭代7 A1：续跑入口同样按归属校验
            workflow = workflowService.getOwnedWorkflow(id);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }

        try {
            WorkflowExecutor executor = engineSelector.selectEngine(workflow);
            // DAG 引擎基于节点快照续跑；LangGraph 引擎基于 Checkpoint 续跑
            if (executor instanceof WorkflowEngine workflowEngine) {
                ExecutionResponse response = workflowEngine.resumeExecution(workflow, executionId, request, null);
                return Result.success(response);
            }
            if (executor instanceof LangGraphWorkflowEngine langGraphEngine) {
                ExecutionResponse response = langGraphEngine.resumeExecution(workflow, executionId, request, null);
                return Result.success(response);
            }
            return Result.error("当前执行引擎暂不支持断点续执行");
        } catch (Exception e) {
            log.error("断点续执行失败", e);
            return Result.error("断点续执行失败: " + e.getMessage());
        }
    }
    
    @Operation(summary = "实时执行工作流(SSE)")
    @GetMapping(value = "/{id}/execute/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter executeWorkflowStream(@PathVariable Long id, @RequestParam String inputData) {
        SseEmitter emitter = new SseEmitter(300000L);
        String emitterId = id + "_" + System.currentTimeMillis();
        emitters.put(emitterId, emitter);
        
        emitter.onCompletion(() -> emitters.remove(emitterId));
        emitter.onTimeout(() -> emitters.remove(emitterId));
        emitter.onError((e) -> emitters.remove(emitterId));
        
        Consumer<ExecutionEvent> eventCallback = event -> {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.getEventType())
                        .data(event));
            } catch (IOException e) {
                log.error("发送 SSE 事件失败", e);
                emitters.remove(emitterId);
            }
        };
        
        // 迭代7：归属校验与上下文快照必须在【请求线程】内完成——
        // 执行线程没有 HTTP 上下文（ThreadLocal 不继承），若把校验放进子线程，
        // owner 会退回 system 导致误判「无权访问」。
        Workflow workflow;
        try {
            workflow = workflowService.getOwnedWorkflow(id);
        } catch (IllegalArgumentException e) {
            sendStreamError(emitter, e.getMessage());
            return emitter;
        }
        String owner = UserContext.currentOwner();
        String tenantId = TenantContext.getCurrentOrDefault("global");
        
        // 迭代7：有界线程池替代裸线程；显式恢复用户/租户上下文，执行完清理防串扰
        sseExecutor.execute(() -> {
            UserContext.setCurrent(owner);
            TenantContext.setCurrent(tenantId);
            try {
                // 使用引擎选择器选择合适的执行引擎
                WorkflowExecutor executor = engineSelector.selectEngine(workflow);
                executor.executeWithCallback(workflow, inputData, eventCallback);
                emitter.complete();
            } catch (Exception e) {
                log.error("工作流执行失败", e);
                sendStreamError(emitter, e.getMessage());
            } finally {
                UserContext.clear();
                TenantContext.clear();
            }
        });
        
        return emitter;
    }

    /** 向 SSE 通道推送一条失败事件并结束（迭代7：抽公共方法，消除重复 try-catch）。 */
    private void sendStreamError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("ERROR")
                    .data(ExecutionEvent.workflowComplete("FAILED", message, 0)));
        } catch (IOException ex) {
            log.error("发送错误事件失败", ex);
        }
        emitter.complete();
    }

    private List<ExecutionResponse.NodeResult> parseNodeResults(String nodeResults) {
        if (nodeResults == null || nodeResults.isBlank()) {
            return Collections.emptyList();
        }

        try {
            return JSON.parseArray(nodeResults, ExecutionResponse.NodeResult.class);
        } catch (Exception e) {
            log.warn("解析执行节点结果失败", e);
            return Collections.emptyList();
        }
    }

    private ExecutionSnapshotResponse toSnapshotResponse(ExecutionSnapshot snapshot) {
        ExecutionSnapshotResponse response = new ExecutionSnapshotResponse();
        response.setId(snapshot.getId());
        response.setExecutionId(snapshot.getExecutionId());
        response.setFlowId(snapshot.getFlowId());
        response.setNodeId(snapshot.getNodeId());
        response.setNodeType(snapshot.getNodeType());
        response.setNodeName(snapshot.getNodeName());
        response.setStatus(snapshot.getStatus());
        response.setInputData(parseJsonMap(snapshot.getInputData()));
        response.setOutputData(parseJsonMap(snapshot.getOutputData()));
        response.setErrorMessage(snapshot.getErrorMessage());
        response.setStartedAt(snapshot.getStartedAt());
        response.setCompletedAt(snapshot.getCompletedAt());
        response.setDuration(snapshot.getDuration());
        response.setRetryCount(snapshot.getRetryCount());
        response.setExecutionOrder(snapshot.getExecutionOrder());
        response.setCreatedAt(snapshot.getCreatedAt());
        return response;
    }

    private ExecutionVariableResponse toVariableResponse(ExecutionVariable variable) {
        ExecutionVariableResponse response = new ExecutionVariableResponse();
        response.setId(variable.getId());
        response.setExecutionId(variable.getExecutionId());
        response.setVariableName(variable.getVariableName());
        response.setVariableType(variable.getVariableType());
        response.setVariableValue(variable.getVariableValue());
        response.setIsModified(variable.getIsModified() != null && variable.getIsModified() == 1);
        response.setCreatedAt(variable.getCreatedAt());
        response.setUpdatedAt(variable.getUpdatedAt());
        return response;
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return JSON.parseObject(json);
        } catch (Exception e) {
            log.warn("解析 JSON 对象失败: {}", json, e);
            return Collections.emptyMap();
        }
    }
}
