package com.paiagent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.paiagent.entity.ApprovalRecord;
import com.paiagent.mapper.ApprovalRecordMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApprovalServiceTest {

    /**
     * LambdaUpdateWrapper 需要实体的 TableInfo lambda 缓存；纯单元测试没有
     * Spring/MyBatis 容器，需显式初始化，否则会依测试执行顺序抛
     * "can not find lambda cache for this entity"。
     */
    @BeforeAll
    static void initTableInfoCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ApprovalRecord.class);
    }

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
        // 迭代7 A5：决策改为条件更新（WHERE status='PENDING'），影响行数 1 = 抢到决策权
        when(mapper.update(any(), any())).thenReturn(1);

        ApprovalService service = createService(mapper);
        ApprovalRecord approved = service.approve(42L, "admin", "looks good");

        assertEquals("APPROVED", approved.getStatus());
        assertEquals("admin", approved.getApprover());
        assertEquals("looks good", approved.getDecisionNote());
        assertNotNull(approved.getDecidedAt());
        verify(mapper).update(any(), any());
    }

    @Test
    void shouldRejectPendingRecord() {
        ApprovalRecordMapper mapper = mock(ApprovalRecordMapper.class);
        ApprovalRecord pending = new ApprovalRecord();
        pending.setId(7L);
        pending.setStatus("PENDING");
        when(mapper.selectById(7L)).thenReturn(pending);
        when(mapper.update(any(), any())).thenReturn(1);

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

    /**
     * 迭代7 A5：并发决策竞态——条件更新影响行数为 0 说明另一个并发决策已抢先落库，
     * 此时必须明确报错，不能静默覆盖对方的决策。
     */
    @Test
    void shouldFailWhenConcurrentDecisionAlreadyTaken() {
        ApprovalRecordMapper mapper = mock(ApprovalRecordMapper.class);
        ApprovalRecord pending = new ApprovalRecord();
        pending.setId(42L);
        pending.setStatus("PENDING");
        when(mapper.selectById(42L)).thenReturn(pending);
        // 读到 PENDING 之后、条件更新之前，被另一个审批抢先改掉 → 更新影响行数 0
        when(mapper.update(any(), any())).thenReturn(0);

        ApprovalService service = createService(mapper);
        assertThrows(IllegalStateException.class, () -> service.approve(42L, "admin", "x"));
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
        when(mapper.update(any(), any())).thenReturn(1);

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
        when(mapper.update(any(), any())).thenReturn(1);

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
        when(mapper.update(any(), any())).thenReturn(1);

        ActionLedgerService ledger = mock(ActionLedgerService.class);
        doThrow(new RuntimeException("db down")).when(ledger)
                .updateApprovalDecision(any(), anyBoolean(), any(), any());

        ApprovalService service = createService(mapper, ledger);
        // 账本同步是治理增强，失败不能回滚审批决策本身
        ApprovalRecord approved = assertDoesNotThrow(() -> service.approve(42L, "admin", "ok"));
        assertEquals("APPROVED", approved.getStatus());
    }
}
