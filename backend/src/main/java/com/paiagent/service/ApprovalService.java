package com.paiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.paiagent.entity.ApprovalRecord;
import com.paiagent.mapper.ApprovalRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

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

    private static final String PENDING_STATUS = "PENDING";
    private static final String APPROVED_STATUS = "APPROVED";
    private static final String REJECTED_STATUS = "REJECTED";

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

    /** 通过审批（迭代7 A5：事务 + 条件更新，并发下只有一方生效）。 */
    @Transactional
    public ApprovalRecord approve(Long id, String approver, String note) {
        return decide(id, approver, note, true);
    }

    /** 拒绝审批（迭代7 A5：同上）。 */
    @Transactional
    public ApprovalRecord reject(Long id, String approver, String note) {
        return decide(id, approver, note, false);
    }

    /**
     * 审批决策（迭代7 A5 · 并发竞态修复）。
     *
     * <p>修复前是「select 读 PENDING → updateById 覆盖」的非原子写：并发
     * approve + reject 都会通过 PENDING 检查，最后一次写覆盖前一次，审批状态不可信。
     * 现改为<b>条件更新</b>（{@code WHERE id = ? AND status = 'PENDING'}），
     * 以影响行数判定是否抢到决策权，抢不到即明确报错，不再静默覆盖。
     */
    private ApprovalRecord decide(Long id, String approver, String note, boolean approved) {
        ApprovalRecord record = mapper.selectById(id);
        if (record == null) {
            throw new IllegalArgumentException("审批记录不存在: " + id);
        }
        if (!PENDING_STATUS.equals(record.getStatus())) {
            throw new IllegalStateException("审批已处理，当前状态: " + record.getStatus());
        }

        String targetStatus = approved ? APPROVED_STATUS : REJECTED_STATUS;
        LocalDateTime decidedAt = LocalDateTime.now();
        int updated = mapper.update(null, new LambdaUpdateWrapper<ApprovalRecord>()
                .eq(ApprovalRecord::getId, id)
                .eq(ApprovalRecord::getStatus, PENDING_STATUS)
                .set(ApprovalRecord::getStatus, targetStatus)
                .set(ApprovalRecord::getApprover, approver)
                .set(ApprovalRecord::getDecisionNote, note)
                .set(ApprovalRecord::getDecidedAt, decidedAt));
        if (updated == 0) {
            throw new IllegalStateException("审批已被其他人处理，请刷新后重试: " + id);
        }

        // 内存对象同步为决策后状态，供审计同步与响应返回（无需再查库）
        record.setStatus(targetStatus);
        record.setApprover(approver);
        record.setDecisionNote(note);
        record.setDecidedAt(decidedAt);
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
     * 审批单是否「已获批准且与本次调用匹配」（迭代7 A6 强化）。
     *
     * <p>迭代6 只校验 {@code status == APPROVED}：任意一个已批准的单号都能为
     * <b>任意工具、任意租户</b>的调用做预授权穿透。现要求审批单与本次调用
     * <b>同工具、同租户</b>才放行，把一个审批单的授权范围收敛到它真正审批过的那次调用。
     *
     * @param approvalId 审批单 ID
     * @param toolName   本次调用的工具名
     * @param tenantId   本次调用的租户
     * @return 记录存在、状态为 APPROVED，且工具与租户均匹配时返回 true
     */
    public boolean isApprovedFor(Long approvalId, String toolName, String tenantId) {
        if (approvalId == null) {
            return false;
        }
        ApprovalRecord record = mapper.selectById(approvalId);
        if (record == null || !APPROVED_STATUS.equals(record.getStatus())) {
            return false;
        }
        if (toolName != null && !toolName.equals(record.getToolName())) {
            log.warn("审批单 [{}] 的工具为 [{}]，与本次调用工具 [{}] 不匹配，拒绝预授权穿透",
                    approvalId, record.getToolName(), toolName);
            return false;
        }
        return Objects.equals(tenantId, record.getTenantId());
    }
}
