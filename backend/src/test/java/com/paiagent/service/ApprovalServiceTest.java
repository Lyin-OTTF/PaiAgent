package com.paiagent.service;

import com.paiagent.entity.ApprovalRecord;
import com.paiagent.mapper.ApprovalRecordMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApprovalServiceTest {

    private ApprovalService createService(ApprovalRecordMapper mapper) {
        return createService(mapper, mock(ActionLedgerService.class));
    }

    private ApprovalService createService(ApprovalRecordMapper mapper, ActionLedgerService ledgerService) {
        return new ApprovalService(mapper, ledgerService);
    }

    @Test
    void shouldCreatePendingRecord() {
        ApprovalRecordMapper mapper = mock(ApprovalRecordMapper.class);
        ApprovalService service = createService(mapper);

        ApprovalRecord input = new ApprovalRecord();
        input.setToolName("memory_write");
        ApprovalRecord created = service.create(input);

        assertEquals("PENDING", created.getStatus());
        verify(mapper).insert(input);
    }

    @Test
    void shouldApprovePendingRecord() {
        ApprovalRecordMapper mapper = mock(ApprovalRecordMapper.class);
        ApprovalRecord pending = new ApprovalRecord();
        pending.setId(42L);
        pending.setStatus("PENDING");
        when(mapper.selectById(42L)).thenReturn(pending);

        ApprovalService service = createService(mapper);
        ApprovalRecord approved = service.approve(42L, "admin", "looks good");

        assertEquals("APPROVED", approved.getStatus());
        assertEquals("admin", approved.getApprover());
        assertEquals("looks good", approved.getDecisionNote());
        assertNotNull(approved.getDecidedAt());
        verify(mapper).updateById(pending);
    }

    @Test
    void shouldRejectPendingRecord() {
        ApprovalRecordMapper mapper = mock(ApprovalRecordMapper.class);
        ApprovalRecord pending = new ApprovalRecord();
        pending.setId(7L);
        pending.setStatus("PENDING");
        when(mapper.selectById(7L)).thenReturn(pending);

        ApprovalService service = createService(mapper);
        ApprovalRecord rejected = service.reject(7L, "admin", "denied");

        assertEquals("REJECTED", rejected.getStatus());
        assertEquals("denied", rejected.getDecisionNote());
    }

    @Test
    void shouldThrowWhenApprovingAlreadyDecided() {
        ApprovalRecordMapper mapper = mock(ApprovalRecordMapper.class);
        ApprovalRecord approved = new ApprovalRecord();
        approved.setId(42L);
        approved.setStatus("APPROVED");
        when(mapper.selectById(42L)).thenReturn(approved);

        ApprovalService service = createService(mapper);
        assertThrows(IllegalStateException.class, () -> service.approve(42L, "admin", "again"));
    }

    @Test
    void shouldThrowWhenRecordMissing() {
        ApprovalRecordMapper mapper = mock(ApprovalRecordMapper.class);
        when(mapper.selectById(999L)).thenReturn(null);

        ApprovalService service = createService(mapper);
        assertThrows(IllegalArgumentException.class, () -> service.approve(999L, "admin", "x"));
    }

    @Test
    void shouldListByStatus() {
        ApprovalRecordMapper mapper = mock(ApprovalRecordMapper.class);
        ApprovalRecord r1 = new ApprovalRecord();
        r1.setStatus("PENDING");
        when(mapper.selectList(any())).thenReturn(List.of(r1));

        ApprovalService service = createService(mapper);
        List<ApprovalRecord> list = service.listByStatus("PENDING");
        assertEquals(1, list.size());
    }

    @Test
    void shouldSyncLedgerDecisionOnApprove() {
        ApprovalRecordMapper mapper = mock(ApprovalRecordMapper.class);
        ApprovalRecord pending = new ApprovalRecord();
        pending.setId(42L);
        pending.setStatus("PENDING");
        when(mapper.selectById(42L)).thenReturn(pending);

        ActionLedgerService ledger = mock(ActionLedgerService.class);
        ApprovalService service = createService(mapper, ledger);
        service.approve(42L, "admin", "looks good");

        // 审批决策落地后必须同步审计账本，使 PENDING_APPROVAL 行更新为 APPROVED
        verify(ledger).updateApprovalDecision(42L, true, "admin", "looks good");
    }

    @Test
    void shouldSyncLedgerDecisionOnReject() {
        ApprovalRecordMapper mapper = mock(ApprovalRecordMapper.class);
        ApprovalRecord pending = new ApprovalRecord();
        pending.setId(7L);
        pending.setStatus("PENDING");
        when(mapper.selectById(7L)).thenReturn(pending);

        ActionLedgerService ledger = mock(ActionLedgerService.class);
        ApprovalService service = createService(mapper, ledger);
        service.reject(7L, "admin", "too risky");

        verify(ledger).updateApprovalDecision(7L, false, "admin", "too risky");
    }

    @Test
    void shouldNotFailApprovalWhenLedgerSyncThrows() {
        ApprovalRecordMapper mapper = mock(ApprovalRecordMapper.class);
        ApprovalRecord pending = new ApprovalRecord();
        pending.setId(42L);
        pending.setStatus("PENDING");
        when(mapper.selectById(42L)).thenReturn(pending);

        ActionLedgerService ledger = mock(ActionLedgerService.class);
        doThrow(new RuntimeException("db down")).when(ledger)
                .updateApprovalDecision(any(), anyBoolean(), any(), any());

        ApprovalService service = createService(mapper, ledger);
        // 账本同步是治理增强，失败不能回滚审批决策本身
        ApprovalRecord approved = assertDoesNotThrow(() -> service.approve(42L, "admin", "ok"));
        assertEquals("APPROVED", approved.getStatus());
    }
}
