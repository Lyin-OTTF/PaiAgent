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
 * <p><b>关于「审批通过后恢复执行」的架构结论（迭代1 遗留问题处置记录）</b>：
 * 当前执行语义是「软挂起」——工具转入审批后 ReAct 节点以 {@code approval_required} 收尾，
 * 工作流并不暂停，执行记录正常完成，因此不存在可供 resume 的断点；
 * 且审批记录只存 500 字符入参摘要，无法可靠还原完整参数做补执行。
 * 故本服务在决策落地后做**审计闭环**（把 PENDING_APPROVAL 账本行更新为
 * APPROVED/REJECTED 并追加决策意见），保证人工决策在审计链路中留痕；
 * 若未来要支持真正的「暂停-恢复」HITL，需要重新设计执行状态机与审批参数持久化，
 * 属于独立迭代范围。
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
}
