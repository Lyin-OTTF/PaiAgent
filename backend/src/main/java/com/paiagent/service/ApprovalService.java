package com.paiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.entity.ApprovalRecord;
import com.paiagent.mapper.ApprovalRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 人工审批服务（Human-in-the-Loop）。
 * 提供创建待审批项、通过/拒绝决策，以及按状态查询的能力。
 *
 * <p><b>HITL 闭环演进（迭代1 遗留 → 迭代6 闭环）</b>：
 * 迭代1 时执行语义是「软挂起」——工具转入审批后 ReAct 节点以 {@code approval_required} 收尾，
 * 工作流不暂停、执行记录正常完成，且审批记录只存 500 字符入参摘要，无法还原完整参数补执行，
 * 因此决策落地后只能做审计闭环（PENDING_APPROVAL 账本行更新为 APPROVED/REJECTED）。
 * 迭代6 补齐了真正闭环：审批记录新增 {@code suspendContext} 全量续跑快照，
 * 审批通过后由 {@code HitlResumeService} 凭快照补执行被挂起的工具调用并续跑 ReAct 循环，
 * 最终回写执行记录；补执行状态观测在 {@code resumeStatus}/{@code resumeError}。
 * 无挂起上下文的历史审批仍走纯审计路径（resumeStatus=SKIPPED），行为向后兼容。
 */
@Slf4j
@Service
public class ApprovalService {

    private final ApprovalRecordMapper mapper;
    private final ActionLedgerService ledgerService;

    public ApprovalService(ApprovalRecordMapper mapper, ActionLedgerService ledgerService) {
        this.mapper = mapper;
        this.ledgerService = ledgerService;
    }

    /** 创建一条 PENDING 审批记录。 */
    public ApprovalRecord create(ApprovalRecord record) {
        record.setStatus("PENDING");
        mapper.insert(record);
        return record;
    }

    /** 通过审批。 */
    public ApprovalRecord approve(Long id, String approver, String note) {
        return decide(id, approver, note, true);
    }

    /** 拒绝审批。 */
    public ApprovalRecord reject(Long id, String approver, String note) {
        return decide(id, approver, note, false);
    }

    private ApprovalRecord decide(Long id, String approver, String note, boolean approved) {
        ApprovalRecord record = mapper.selectById(id);
        if (record == null) {
            throw new IllegalArgumentException("审批记录不存在: " + id);
        }
        if (!"PENDING".equals(record.getStatus())) {
            throw new IllegalStateException("审批已处理，当前状态: " + record.getStatus());
        }
        record.setStatus(approved ? "APPROVED" : "REJECTED");
        record.setApprover(approver);
        record.setDecisionNote(note);
        record.setDecidedAt(LocalDateTime.now());
        mapper.updateById(record);
        syncLedgerDecision(record, approved);
        return record;
    }

    /**
     * 审批决策落地后同步审计账本（失败不影响审批主流程，只打 WARN）。
     * 审批决策是主路径，账本同步是治理增强，不能因后者失败回滚前者。
     */
    private void syncLedgerDecision(ApprovalRecord record, boolean approved) {
        try {
            ledgerService.updateApprovalDecision(record.getId(), approved, record.getApprover(), record.getDecisionNote());
        } catch (Exception e) {
            log.warn("同步审批决策到审计账本失败（不影响审批主流程）: approvalId={}", record.getId(), e);
        }
    }

    /** 按状态查询审批记录（status 为空则全部）。 */
    public List<ApprovalRecord> listByStatus(String status) {
        LambdaQueryWrapper<ApprovalRecord> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq(ApprovalRecord::getStatus, status);
        }
        wrapper.orderByDesc(ApprovalRecord::getCreatedAt);
        return mapper.selectList(wrapper);
    }

    /**
     * 审批单是否已获批准（迭代6：ToolGateway 预授权穿透的判定来源）。
     * 记录不存在或非 APPROVED 均返回 false——网关据此决定新建审批还是直接执行。
     */
    public boolean isApproved(Long approvalId) {
        if (approvalId == null) {
            return false;
        }
        ApprovalRecord record = mapper.selectById(approvalId);
        return record != null && "APPROVED".equals(record.getStatus());
    }
}
