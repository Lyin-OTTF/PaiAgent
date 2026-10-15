package com.paiagent.controller;

import com.paiagent.common.Result;
import com.paiagent.dto.ApprovalDecisionRequest;
import com.paiagent.engine.agent.policy.PolicyRegistry;
import com.paiagent.engine.agent.policy.ResolvedPolicy;
import com.paiagent.entity.ApprovalRecord;
import com.paiagent.entity.ActionLedger;
import com.paiagent.service.ActionLedgerService;
import com.paiagent.service.ApprovalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 受限自治层治理接口：审批流（HITL）与工具调用审计。
 */
@Tag(name = "受限自治层治理接口")
@RestController
@RequestMapping("/api/governance")
public class GovernanceController {

    private final ApprovalService approvalService;
    private final ActionLedgerService actionLedgerService;
    private final PolicyRegistry policyRegistry;

    public GovernanceController(ApprovalService approvalService,
                                ActionLedgerService actionLedgerService,
                                PolicyRegistry policyRegistry) {
        this.approvalService = approvalService;
        this.actionLedgerService = actionLedgerService;
        this.policyRegistry = policyRegistry;
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
            String approver = request != null ? request.getApprover() : null;
            String note = request != null ? request.getNote() : null;
            return Result.success(approvalService.approve(id, approver, note));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "拒绝审批")
    @PostMapping("/approvals/{id}/reject")
    public Result<ApprovalRecord> reject(@PathVariable Long id,
                                         @RequestBody(required = false) ApprovalDecisionRequest request) {
        try {
            String approver = request != null ? request.getApprover() : null;
            String note = request != null ? request.getNote() : null;
            return Result.success(approvalService.reject(id, approver, note));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
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
