package com.paiagent.controller;

import com.paiagent.common.Result;
import com.paiagent.common.UserContext;
import com.paiagent.dto.ApprovalDecisionRequest;
import com.paiagent.engine.agent.policy.PolicyRegistry;
import com.paiagent.engine.agent.policy.ResolvedPolicy;
import com.paiagent.entity.ApprovalRecord;
import com.paiagent.entity.ActionLedger;
import com.paiagent.service.ActionLedgerService;
import com.paiagent.service.ApprovalService;
import com.paiagent.service.HitlResumeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 受限自治层治理接口：审批流（HITL）与工具调用审计。
 *
 * <p>迭代6：审批通过后异步触发 {@link HitlResumeService} 补执行——
 * 响应不阻塞续跑（LLM 多步），补执行结果经审批记录 resumeStatus 与执行记录可观测。
 */
@Slf4j
@Tag(name = "受限自治层治理接口")
@RestController
@RequestMapping("/api/governance")
public class GovernanceController {

    private final ApprovalService approvalService;
    private final ActionLedgerService actionLedgerService;
    private final PolicyRegistry policyRegistry;
    private final HitlResumeService hitlResumeService;

    public GovernanceController(ApprovalService approvalService,
                                ActionLedgerService actionLedgerService,
                                PolicyRegistry policyRegistry,
                                HitlResumeService hitlResumeService) {
        this.approvalService = approvalService;
        this.actionLedgerService = actionLedgerService;
        this.policyRegistry = policyRegistry;
        this.hitlResumeService = hitlResumeService;
    }

    @Operation(summary = "查询审批列表（可按状态过滤：PENDING/APPROVED/REJECTED）")
    @GetMapping("/approvals")
    public Result<List<ApprovalRecord>> listApprovals(@RequestParam(required = false) String status) {
        return Result.success(approvalService.listByStatus(status));
    }

    @Operation(summary = "通过审批")
    @PostMapping("/approvals/{id}/approve")
    public Result<ApprovalRecord> approve(@PathVariable Long id,
                                          @RequestBody(required = false) ApprovalDecisionRequest request) {
        try {
            // 迭代7 A7：审批人取服务端会话身份，不再接受请求体传入——
            // 修复前 approver 由前端自由填写，审计链上的「谁批的」可被随意伪造。
            String approver = UserContext.currentOwner();
            String note = request != null ? request.getNote() : null;
            ApprovalRecord approved = approvalService.approve(id, approver, note);
            // 迭代6：审批通过即触发补执行（异步，不阻塞响应）；
            // 无挂起上下文的历史审批内部标记 SKIPPED，保持审计闭环行为
            hitlResumeService.resumeAsync(approved);
            return Result.success(approved);
        } catch (RuntimeException e) {
            // 迭代10：不再无条件回显 e.getMessage()——业务异常（记录不存在/状态冲突）
            // 消息为可读文案，但未预期异常（NPE/DB 错误）的 message 可能含内部细节
            log.error("审批通过失败, id={}", id, e);
            return Result.error(sanitizeApprovalError(e));
        }
    }

    @Operation(summary = "拒绝审批")
    @PostMapping("/approvals/{id}/reject")
    public Result<ApprovalRecord> reject(@PathVariable Long id,
                                         @RequestBody(required = false) ApprovalDecisionRequest request) {
        try {
            // 迭代7 A7：拒绝操作同样以会话身份作为审批人
            String approver = UserContext.currentOwner();
            String note = request != null ? request.getNote() : null;
            return Result.success(approvalService.reject(id, approver, note));
        } catch (RuntimeException e) {
            // 迭代10：同 approve——业务异常保留可读消息，未预期异常只回通用文案
            log.error("审批拒绝失败, id={}", id, e);
            return Result.error(sanitizeApprovalError(e));
        }
    }

    /**
     * 审批异常脱敏：业务参数/状态异常（{@link IllegalArgumentException} /
     * {@link IllegalStateException}）的消息为可读业务文案，直接回显；
     * 其余未预期异常只回通用文案，细节仅入日志（与 {@code GlobalExceptionHandler} 策略一致）。
     */
    private static String sanitizeApprovalError(RuntimeException e) {
        return e instanceof IllegalArgumentException || e instanceof IllegalStateException
                ? e.getMessage()
                : "审批操作失败，请稍后重试";
    }

    @Operation(summary = "查询工具调用审计日志（可按工具名/动作状态过滤）")
    @GetMapping("/ledger")
    public Result<List<ActionLedger>> listLedger(@RequestParam(required = false) String toolName,
                                                 @RequestParam(required = false) String actionStatus,
                                                 @RequestParam(defaultValue = "100") int limit) {
        return Result.success(actionLedgerService.query(toolName, actionStatus, limit));
    }

    @Operation(summary = "展示当前工具权限分级（治理策略快照）")
    @GetMapping("/policies")
    public Result<List<Map<String, Object>>> listPolicies() {
        List<Map<String, Object>> snapshot = new java.util.ArrayList<>();
        for (Map.Entry<String, ResolvedPolicy> entry : policyRegistry.allPolicies().entrySet()) {
            ResolvedPolicy p = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("toolName", entry.getKey());
            item.put("level", p.level().name());
            item.put("write", p.isWrite());
            item.put("scope", p.scope());
            item.put("idempotent", p.isIdempotent());
            item.put("requiresApproval", p.isRequiresApproval());
            snapshot.add(item);
        }
        return Result.success(snapshot);
    }
}
