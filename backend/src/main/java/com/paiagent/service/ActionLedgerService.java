package com.paiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.entity.ActionLedger;
import com.paiagent.mapper.ActionLedgerMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 工具调用审计服务。所有写操作均通过 {@code ActionLedger} 留痕，保证操作链路可追溯。
 */
@Service
public class ActionLedgerService {

    private final ActionLedgerMapper mapper;

    public ActionLedgerService(ActionLedgerMapper mapper) {
        this.mapper = mapper;
    }

    /** 落一条审计记录（插入并返回带主键的实体）。 */
    public ActionLedger record(ActionLedger ledger) {
        mapper.insert(ledger);
        return ledger;
    }

    /**
     * 审批决策后同步审计账本（迭代1 HITL 闭环治理）。
     *
     * <p>把该审批关联的 {@code PENDING_APPROVAL} 账本行更新为 {@code APPROVED/REJECTED}，
     * 并在结果摘要追加决策人与意见——否则账本永远停留在「已转入人工审批」，
     * 人工决策的最终命运在审计链路中无痕（迭代1 遗留问题）。
     *
     * <p>实现说明：先查后改（Java 侧拼接摘要）而不是 {@code setSql} 拼接，
     * 避免决策人/意见等不可信入参进入 SQL 文本造成注入面。
     *
     * @return 更新行数；0 表示没有关联的待审批账本行（如未经网关创建的审批单）
     */
    public int updateApprovalDecision(Long approvalId, boolean approved, String approver, String note) {
        if (approvalId == null) {
            return 0;
        }
        LambdaQueryWrapper<ActionLedger> query = new LambdaQueryWrapper<>();
        query.eq(ActionLedger::getApprovalId, approvalId)
                .eq(ActionLedger::getActionStatus, "PENDING_APPROVAL");
        List<ActionLedger> pendingRows = mapper.selectList(query);
        if (pendingRows.isEmpty()) {
            return 0;
        }
        String decision = String.format("审批人 %s %s%s",
                approver == null ? "(未署名)" : approver,
                approved ? "通过" : "拒绝",
                note == null || note.isBlank() ? "" : "：" + note.trim());
        int updated = 0;
        for (ActionLedger row : pendingRows) {
            row.setActionStatus(approved ? "APPROVED" : "REJECTED");
            String previous = row.getResultSummary();
            row.setResultSummary(previous == null || previous.isBlank()
                    ? decision
                    : previous + " | " + decision);
            updated += mapper.updateById(row);
        }
        return updated;
    }

    /** 按工具名/动作状态分页查询审计日志。 */
    public List<ActionLedger> query(String toolName, String actionStatus, int limit) {
        LambdaQueryWrapper<ActionLedger> wrapper = new LambdaQueryWrapper<>();
        if (toolName != null && !toolName.isBlank()) {
            wrapper.eq(ActionLedger::getToolName, toolName);
        }
        if (actionStatus != null && !actionStatus.isBlank()) {
            wrapper.eq(ActionLedger::getActionStatus, actionStatus);
        }
        wrapper.orderByDesc(ActionLedger::getCreatedAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 500)));
        return mapper.selectList(wrapper);
    }
}
