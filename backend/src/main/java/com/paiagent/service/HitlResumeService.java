package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.paiagent.engine.WorkflowConfigParser;
import com.paiagent.engine.agent.AgentTool;
import com.paiagent.engine.agent.AgentToolContext;
import com.paiagent.engine.agent.AgentToolRegistry;
import com.paiagent.engine.agent.gateway.GatewayStatus;
import com.paiagent.engine.agent.gateway.ToolExecutionContext;
import com.paiagent.engine.agent.gateway.ToolGateway;
import com.paiagent.engine.agent.gateway.ToolGatewayResult;
import com.paiagent.engine.agent.hitl.HitlSuspendContext;
import com.paiagent.engine.executor.impl.ReActAgentNodeExecutor;
import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.entity.ApprovalRecord;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.ApprovalRecordMapper;
import com.paiagent.mapper.ExecutionRecordMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HITL 审批恢复服务（迭代6）：审批通过后的补执行闭环。
 *
 * <p><b>链路</b>：GovernanceController 审批通过 → {@link #resumeAsync} 提交异步任务 →
 * 凭审批记录中的挂起快照定位工作流节点 → 以「已获批审批单」为预授权经 ToolGateway
 * 补执行被挂起的那一次工具调用（幂等守卫/租户校验/审计照常生效）→
 * 调 {@link ReActAgentNodeExecutor#resumeAfterApproval} 从断点续跑 ReAct 循环 →
 * 回写执行记录（该节点输出 + 工作流输出）→ 更新审批记录的补执行状态。
 *
 * <p><b>为什么异步</b>：续跑要调 LLM（可能多步），不能阻塞审批 HTTP 响应；
 * 单线程池串行恢复，避免并发恢复互相踩踏；补执行成败都落库可观测
 * （{@code resumeStatus}/{@code resumeError}），调用方轮询审批列表或执行记录即可。
 *
 * <p><b>语义边界（如实记录）</b>：快照只覆盖 ReAct 节点内部循环。若挂起节点下游
 * 还有其他节点，它们在挂起时已基于 pending 输出执行过，补执行不重放它们——
 * 图级「暂停-恢复」需要执行状态机重构，属独立迭代范围。
 * 无挂起上下文的历史审批（含非 ReAct 来源）标记 SKIPPED，保持旧审计闭环行为。
 */
@Slf4j
@Service
public class HitlResumeService {

    private final ApprovalRecordMapper approvalRecordMapper;
    private final ExecutionRecordMapper executionRecordMapper;
    private final WorkflowService workflowService;
    private final WorkflowConfigParser workflowConfigParser;
    private final AgentToolRegistry toolRegistry;
    private final ToolGateway toolGateway;
    private final ReActAgentNodeExecutor reactAgentNodeExecutor;
    private final ExecutorService resumeExecutor;

    public HitlResumeService(ApprovalRecordMapper approvalRecordMapper,
                             ExecutionRecordMapper executionRecordMapper,
                             WorkflowService workflowService,
                             WorkflowConfigParser workflowConfigParser,
                             AgentToolRegistry toolRegistry,
                             ToolGateway toolGateway,
                             ReActAgentNodeExecutor reactAgentNodeExecutor) {
        this.approvalRecordMapper = approvalRecordMapper;
        this.executionRecordMapper = executionRecordMapper;
        this.workflowService = workflowService;
        this.workflowConfigParser = workflowConfigParser;
        this.toolRegistry = toolRegistry;
        this.toolGateway = toolGateway;
        this.reactAgentNodeExecutor = reactAgentNodeExecutor;
        // 单线程串行恢复：治理场景低频，串行足够且避免并发 LLM 踩踏
        this.resumeExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "hitl-resume");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    public void shutdown() {
        resumeExecutor.shutdown();
        log.info("HITL 恢复线程池已关闭");
    }

    /**
     * 提交异步补执行。审批通过后立即返回，不阻塞审批响应。
     * 重复触发安全：已处理过（resumeStatus 非空）的记录直接跳过。
     */
    public void resumeAsync(ApprovalRecord record) {
        if (record == null || record.getId() == null) {
            return;
        }
        Long approvalId = record.getId();
        resumeExecutor.execute(() -> {
            try {
                doResume(approvalId);
            } catch (Exception e) {
                // 兜底：doResume 内部已分级捕获，此处防线程任务意外逃逸
                log.error("HITL 补执行任务异常逃逸: approvalId={}", approvalId, e);
            }
        });
    }

    /**
     * 同步补执行主流程（包级可见便于测试驱动；线上由 {@link #resumeAsync} 提交执行）。
     *
     * @param approvalId 审批记录 ID（内部重新读取最新状态，避免读到调用方过期快照）
     */
    void doResume(Long approvalId) {
        ApprovalRecord record = approvalRecordMapper.selectById(approvalId);
        if (record == null) {
            log.warn("HITL 补执行跳过：审批记录不存在: {}", approvalId);
            return;
        }
        if (record.getResumeStatus() != null) {
            log.info("HITL 补执行跳过：已处理过（resumeStatus={}）: {}", record.getResumeStatus(), approvalId);
            return;
        }
        if (record.getSuspendContext() == null || record.getSuspendContext().isBlank()) {
            // 无挂起上下文：非 ReAct 审批或历史数据，保持旧审计闭环行为
            markSkipped(record, "无挂起上下文（非 ReAct 审批或历史数据），仅审计闭环");
            return;
        }

        HitlSuspendContext snapshot;
        try {
            snapshot = HitlSuspendContext.fromJson(record.getSuspendContext());
        } catch (Exception e) {
            markFailed(record, "挂起上下文解析失败: " + e.getMessage());
            return;
        }
        if (snapshot == null || snapshot.executionId() == null || snapshot.toolName() == null) {
            markFailed(record, "挂起上下文缺少必要字段（executionId/toolName）");
            return;
        }

        try {
            WorkflowNode node = resolveNode(snapshot);
            AgentTool tool = toolRegistry.getRequiredTool(snapshot.toolName());

            // 预授权补执行：allowedLevel 保持挂起时的运行时等级（不人为抬升），
            // 调用能否穿透完全依赖网关复核「这张审批单确实已获批」——
            // 审批单被拒/撤销时调用仍会被拦截，不会借恢复通道绕过治理。
            ToolExecutionContext resumeCtx = new ToolExecutionContext(
                    snapshot.tenantId(), snapshot.allowedLevel(), snapshot.caller(),
                    snapshot.executionId(), snapshot.workflowId(), snapshot.nodeId(),
                    null, record.getId());

            ToolGatewayResult gatewayResult = toolGateway.execute(
                    tool, snapshot.toolInput(), resumeCtx, new AgentToolContext(node, snapshot.input()));
            if (gatewayResult.status() != GatewayStatus.EXECUTED) {
                markFailed(record, "工具补执行未成功（" + gatewayResult.status() + "）: " + gatewayResult.message());
                return;
            }

            Map<String, Object> finalOutput = reactAgentNodeExecutor.resumeAfterApproval(
                    node, snapshot, gatewayResult.output(), record.getId());

            amendExecutionRecord(snapshot, finalOutput);

            record.setResumeStatus("SUCCEEDED");
            record.setResumeError(null);
            approvalRecordMapper.updateById(record);
            log.info("HITL 补执行完成: approvalId={}, executionId={}, nodeId={}",
                    record.getId(), snapshot.executionId(), snapshot.nodeId());
        } catch (Exception e) {
            log.error("HITL 补执行失败: approvalId={}", approvalId, e);
            markFailed(record, e.getMessage());
        }
    }

    /** 从工作流定义解析挂起节点（工作流被删/节点被删时给出明确失败原因）。 */
    private WorkflowNode resolveNode(HitlSuspendContext snapshot) {
        if (snapshot.workflowId() == null) {
            throw new IllegalStateException("挂起上下文缺少 workflowId");
        }
        Workflow workflow = workflowService.getById(snapshot.workflowId());
        if (workflow == null) {
            throw new IllegalStateException("工作流不存在: " + snapshot.workflowId());
        }
        WorkflowConfig config = workflowConfigParser.parse(workflow.getFlowData());
        if (config.getNodes() != null) {
            for (WorkflowNode node : config.getNodes()) {
                if (snapshot.nodeId().equals(node.getId())) {
                    return node;
                }
            }
        }
        throw new IllegalStateException("工作流 [" + snapshot.workflowId() + "] 中不存在节点: " + snapshot.nodeId());
    }

    /**
     * 回写执行记录：把挂起节点的输出替换为补执行后的最终产物，并同步工作流输出。
     *
     * <p>nodeResults 的条目结构（DAG/LangGraph 两引擎一致）：nodeId/nodeName/status/output/error，
     * output 为 JSON 字符串，因此按 nodeId 定位替换，与引擎实现解耦。
     */
    private void amendExecutionRecord(HitlSuspendContext snapshot, Map<String, Object> finalOutput) {
        ExecutionRecord record = executionRecordMapper.selectById(snapshot.executionId());
        if (record == null) {
            throw new IllegalStateException("执行记录不存在: " + snapshot.executionId());
        }
        if (record.getNodeResults() == null || record.getNodeResults().isBlank()) {
            throw new IllegalStateException("执行记录 [" + snapshot.executionId() + "] 无节点结果，无法回写");
        }
        record.setNodeResults(amendNodeResults(record.getNodeResults(), snapshot.nodeId(), finalOutput));
        record.setOutputData(JSON.toJSONString(finalOutput));
        executionRecordMapper.updateById(record);
    }

    /** 静态工具方法（包级可见）：替换 nodeResults 中指定节点的 output。 */
    static String amendNodeResults(String nodeResultsJson, String nodeId, Map<String, Object> finalOutput) {
        List<Object> parsed = JSON.parseArray(nodeResultsJson);
        List<Object> amended = new ArrayList<>(parsed);
        boolean replaced = false;
        for (Object item : amended) {
            if (item instanceof Map<?, ?> entry && nodeId.equals(entry.get("nodeId"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mutable = (Map<String, Object>) entry;
                mutable.put("output", JSON.toJSONString(finalOutput));
                replaced = true;
            }
        }
        if (!replaced) {
            throw new IllegalStateException("nodeResults 中未找到节点: " + nodeId);
        }
        return JSON.toJSONString(amended);
    }

    private void markSkipped(ApprovalRecord record, String reason) {
        record.setResumeStatus("SKIPPED");
        record.setResumeError(reason);
        approvalRecordMapper.updateById(record);
        log.info("HITL 补执行跳过: approvalId={}, reason={}", record.getId(), reason);
    }

    private void markFailed(ApprovalRecord record, String reason) {
        record.setResumeStatus("FAILED");
        // 防御：失败原因截断，避免超长堆栈撑爆 TEXT 列
        String truncated = reason == null ? "unknown" : reason;
        record.setResumeError(truncated.length() > 2000 ? truncated.substring(0, 2000) : truncated);
        approvalRecordMapper.updateById(record);
    }
}
