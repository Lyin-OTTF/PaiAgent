package com.paiagent.service;

import com.paiagent.entity.ActionLedger;
import com.paiagent.mapper.ActionLedgerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ActionLedgerService 单元测试（Mockito，无 DB）。
 *
 * 重点验证迭代1 HITL 闭环治理：审批决策同步账本
 * （PENDING_APPROVAL → APPROVED/REJECTED，决策意见追加到结果摘要）。
 */
class ActionLedgerServiceTest {

    private ActionLedgerMapper mapper;
    private ActionLedgerService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ActionLedgerMapper.class);
        service = new ActionLedgerService(mapper);
    }

    @Test
    void shouldMarkApprovedAndAppendDecision() {
        ActionLedger row = new ActionLedger();
        row.setId(1L);
        row.setApprovalId(42L);
        row.setActionStatus("PENDING_APPROVAL");
        row.setResultSummary("工具所需自治等级(ESCALATE)超过运行时允许(OBSERVE)，已转入人工审批(42)");
        when(mapper.selectList(any())).thenReturn(List.of(row));
        when(mapper.updateById(row)).thenReturn(1);

        int updated = service.updateApprovalDecision(42L, true, "admin", "已核实，同意执行");

        assertEquals(1, updated);
        assertEquals("APPROVED", row.getActionStatus());
        // 原摘要保留，决策追加在后，审计链路完整
        assertEquals("工具所需自治等级(ESCALATE)超过运行时允许(OBSERVE)，已转入人工审批(42)"
                + " | 审批人 admin 通过：已核实，同意执行", row.getResultSummary());
        verify(mapper, times(1)).updateById(row);
    }

    @Test
    void shouldMarkRejectedAndAppendDecision() {
        ActionLedger row = new ActionLedger();
        row.setId(2L);
        row.setApprovalId(7L);
        row.setActionStatus("PENDING_APPROVAL");
        row.setResultSummary("已转入人工审批(7)");
        when(mapper.selectList(any())).thenReturn(List.of(row));
        when(mapper.updateById(row)).thenReturn(1);

        service.updateApprovalDecision(7L, false, "admin", "风险过高");

        assertEquals("REJECTED", row.getActionStatus());
        assertTrue(row.getResultSummary().contains("审批人 admin 拒绝：风险过高"));
    }

    @Test
    void shouldHandleBlankSummaryAndNullNote() {
        ActionLedger row = new ActionLedger();
        row.setId(3L);
        row.setApprovalId(9L);
        row.setActionStatus("PENDING_APPROVAL");
        row.setResultSummary(null);
        when(mapper.selectList(any())).thenReturn(List.of(row));
        when(mapper.updateById(row)).thenReturn(1);

        service.updateApprovalDecision(9L, true, null, null);

        // 无原摘要时直接写决策；审批人为空时给占位；意见为空时不冒号结尾
        assertEquals("APPROVED", row.getActionStatus());
        assertEquals("审批人 (未署名) 通过", row.getResultSummary());
    }

    @Test
    void shouldDoNothingWhenNoPendingLedgerRow() {
        when(mapper.selectList(any())).thenReturn(List.of());

        int updated = service.updateApprovalDecision(99L, true, "admin", "x");

        assertEquals(0, updated);
        verify(mapper, never()).updateById(any());
    }

    @Test
    void shouldIgnoreNullApprovalId() {
        assertEquals(0, service.updateApprovalDecision(null, true, "admin", "x"));
        verify(mapper, never()).selectList(any());
    }

    @Test
    void shouldUpdateAllPendingRowsOfSameApproval() {
        ActionLedger r1 = ledgerRow(1L, "PENDING_APPROVAL", "first");
        ActionLedger r2 = ledgerRow(2L, "PENDING_APPROVAL", "second");
        when(mapper.selectList(any())).thenReturn(List.of(r1, r2));
        when(mapper.updateById(any())).thenReturn(1);

        int updated = service.updateApprovalDecision(42L, true, "admin", "ok");

        assertEquals(2, updated);
        assertEquals("APPROVED", r1.getActionStatus());
        assertEquals("APPROVED", r2.getActionStatus());
    }

    private ActionLedger ledgerRow(Long id, String status, String summary) {
        ActionLedger row = new ActionLedger();
        row.setId(id);
        row.setApprovalId(42L);
        row.setActionStatus(status);
        row.setResultSummary(summary);
        return row;
    }
}
