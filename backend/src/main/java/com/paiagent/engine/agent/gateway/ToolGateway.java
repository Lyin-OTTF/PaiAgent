package com.paiagent.engine.agent.gateway;

import com.alibaba.fastjson2.JSON;
import com.paiagent.engine.agent.AgentTool;
import com.paiagent.engine.agent.AgentToolContext;
import com.paiagent.engine.agent.policy.AutonomyLevel;
import com.paiagent.engine.agent.policy.PolicyRegistry;
import com.paiagent.engine.agent.policy.ResolvedPolicy;
import com.paiagent.entity.ActionLedger;
import com.paiagent.entity.ApprovalRecord;
import com.paiagent.service.ActionLedgerService;
import com.paiagent.service.ApprovalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具网关（受限自治层的核心执行拦截点）。
 *
 * 执行前决策顺序：
 * 1. 跨租户校验 —— 写类工具的作用域与调用方租户不一致则拒绝；
 * 2. 自治等级校验 —— 工具所需等级高于运行时允许等级时，进入审批流或拒绝；
 *    （迭代6：ctx 携带已获批审批单 = 人类预授权，跳过新建审批直接穿透执行）；
 * 3. 幂等守卫 —— 幂等工具在 TTL 内重复调用直接返回首次结果；
 * 4. 执行并审计 —— 调用真实工具，结果写入 ActionLedger。
 *
 * 对「被允许且未越权」的工具，网关对调用方透明（返回与直接调用一致的结果），
 * 因此接入网关不会破坏既有工具行为。
 */
@Slf4j
@Service
public class ToolGateway {

    private static final int SUMMARY_MAX = 500;

    private final PolicyRegistry policyRegistry;
    private final IdempotencyGuard idempotencyGuard;
    private final ActionLedgerService ledgerService;
    private final ApprovalService approvalService;

    public ToolGateway(PolicyRegistry policyRegistry,
                      IdempotencyGuard idempotencyGuard,
                      ActionLedgerService ledgerService,
                      ApprovalService approvalService) {
        this.policyRegistry = policyRegistry;
        this.idempotencyGuard = idempotencyGuard;
        this.ledgerService = ledgerService;
        this.approvalService = approvalService;
    }

    public ToolGatewayResult execute(AgentTool tool,
                                     Map<String, Object> args,
                                     ToolExecutionContext ctx,
                                     AgentToolContext toolContext) throws Exception {
        String toolName = tool.getName();
        ResolvedPolicy policy = policyRegistry.resolve(toolName);
        String idemKey = buildIdempotencyKey(toolName, ctx.tenantId(), args);

        // 1. 跨租户校验
        // 迭代7（中危修复）：写操作缺失租户上下文时「拒绝」而非放行——
        // 修复前条件含 ctx.tenantId() != null，null 租户的写调用会直接跳过校验。
        if (policy.isWrite()) {
            if (ctx.tenantId() == null || ctx.tenantId().isBlank()) {
                String msg = "写操作缺少租户上下文，已拒绝";
                recordLedger(ctx, toolName, policy, args, "DENIED", msg, null, null, idemKey);
                return ToolGatewayResult.denied(msg);
            }
            if (!"global".equals(policy.scope())
                    && !"shared".equals(policy.scope())
                    && !policy.scope().equals(ctx.tenantId())) {
                String msg = String.format("跨租户操作被拒绝：工具作用域=%s，调用方租户=%s", policy.scope(), ctx.tenantId());
                recordLedger(ctx, toolName, policy, args, "DENIED", msg, null, null, idemKey);
                return ToolGatewayResult.denied(msg);
            }
        }

        // 2. 自治等级校验
        if (policy.level().isHigherThan(ctx.allowedLevel())) {
            boolean needApproval = policy.isRequiresApproval() || policy.level() == AutonomyLevel.ESCALATE;
            // 迭代6：携带已获批审批单的调用视为「这一次特定调用已获人类预授权」，
            // 不再新建审批单，直接穿透执行（租户校验/幂等/审计不受影响）。
            // 迭代7 A6：预授权还需「审批单 + 工具 + 租户」三者匹配，
            // 否则任意一个已批准的单号都能为任意工具做穿透。
            boolean preApproved = ctx.approvalId() != null
                    && approvalService.isApprovedFor(ctx.approvalId(), toolName, ctx.tenantId());
            if (preApproved) {
                log.info("工具 [{}] 凭已获批审批单 [{}] 预授权执行", toolName, ctx.approvalId());
            } else if (needApproval) {
                ApprovalRecord approval = new ApprovalRecord();
                approval.setExecutionId(ctx.executionId());
                approval.setWorkflowId(ctx.workflowId());
                approval.setNodeId(ctx.nodeId());
                approval.setToolName(toolName);
                approval.setCaller(ctx.caller());
                approval.setTenantId(ctx.tenantId());
                approval.setInputSummary(summarize(args));
                // 迭代6：挂起上下文全量快照落库（修复前仅 500 字符摘要，无法还原续跑）
                if (ctx.suspendContext() != null) {
                    approval.setSuspendContext(JSON.toJSONString(ctx.suspendContext()));
                }
                approval.setRequestedLevel(policy.level().name());
                ApprovalRecord saved = approvalService.create(approval);
                String msg = String.format("工具所需自治等级(%s)超过运行时允许(%s)，已转入人工审批(%d)",
                        policy.level().name(), ctx.allowedLevel().name(), saved.getId());
                recordLedger(ctx, toolName, policy, args, "PENDING_APPROVAL", msg, null, saved.getId(), idemKey);
                return ToolGatewayResult.pendingApproval(saved.getId(), msg);
            } else {
                String msg = String.format("工具所需自治等级(%s)超过运行时允许(%s)，且无审批配置，已拒绝",
                        policy.level().name(), ctx.allowedLevel().name());
                recordLedger(ctx, toolName, policy, args, "DENIED", msg, null, null, idemKey);
                return ToolGatewayResult.denied(msg);
            }
        }

        // 3. 幂等守卫
        if (policy.isIdempotent()) {
            Map<String, Object> cached = idempotencyGuard.getIfPresent(idemKey);
            if (cached != null) {
                // 迭代6：恢复执行命中幂等缓存时同样关联审批单，审计链完整
                recordLedger(ctx, toolName, policy, args, "EXECUTED", "命中幂等缓存", summarize(cached), ctx.approvalId(), idemKey);
                return ToolGatewayResult.executed(cached, "命中幂等缓存");
            }
        }

        // 4. 执行并审计
        try {
            Map<String, Object> output = tool.execute(args, toolContext);
            Map<String, Object> safeOutput = output != null ? output : new LinkedHashMap<>();
            if (policy.isIdempotent()) {
                idempotencyGuard.put(idemKey, safeOutput);
            }
            recordLedger(ctx, toolName, policy, args, "EXECUTED", null, summarize(safeOutput), ctx.approvalId(), idemKey);
            return ToolGatewayResult.executed(safeOutput, null);
        } catch (Exception e) {
            recordLedger(ctx, toolName, policy, args, "ERROR", e.getMessage(), null, ctx.approvalId(), idemKey);
            throw e;
        }
    }

    private void recordLedger(ToolExecutionContext ctx, String toolName, ResolvedPolicy policy,
                              Map<String, Object> args, String status, String message,
                              String resultSummary, Long approvalId, String idemKey) {
        try {
            ActionLedger ledger = new ActionLedger();
            ledger.setExecutionId(ctx.executionId());
            ledger.setWorkflowId(ctx.workflowId());
            ledger.setNodeId(ctx.nodeId());
            ledger.setToolName(toolName);
            ledger.setCaller(ctx.caller());
            ledger.setTenantId(ctx.tenantId());
            ledger.setInputSummary(summarize(args));
            ledger.setAutonomyLevel(policy.level().name());
            ledger.setWriteFlag(policy.isWrite() ? 1 : 0);
            ledger.setActionStatus(status);
            ledger.setResultSummary(resultSummary);
            if ("ERROR".equals(status) && message != null) {
                ledger.setErrorMessage(message);
            } else if (resultSummary == null && message != null) {
                ledger.setResultSummary(message);
            }
            ledger.setApprovalId(approvalId);
            ledger.setIdempotencyKey(idemKey);
            ledgerService.record(ledger);
        } catch (Exception e) {
            log.warn("写入 ActionLedger 失败（不影响主流程）: {}", e.getMessage());
        }
    }

    /**
     * 生成幂等键（迭代6修复：委托 {@link IdempotencyKeys}）。
     *
     * <p>旧实现 {@code Integer.toHexString(base.hashCode())} 存在 32 位哈希碰撞
     * （不同入参可能被判为同一请求而错误命中）与 Map 序列化顺序不稳定问题。
     */
    private String buildIdempotencyKey(String toolName, String tenantId, Map<String, Object> args) {
        return IdempotencyKeys.build(tenantId, toolName, args);
    }

    private String summarize(Object value) {
        if (value == null) {
            return null;
        }
        String text = value instanceof String s ? s : JSON.toJSONString(value);
        return text.length() > SUMMARY_MAX ? text.substring(0, SUMMARY_MAX) : text;
    }
}
