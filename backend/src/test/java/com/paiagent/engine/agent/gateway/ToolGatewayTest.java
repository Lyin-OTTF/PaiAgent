package com.paiagent.engine.agent.gateway;

import com.paiagent.engine.agent.AgentTool;
import com.paiagent.engine.agent.AgentToolContext;
import com.paiagent.engine.agent.policy.AutonomyLevel;
import com.paiagent.engine.agent.policy.PolicyRegistry;
import com.paiagent.engine.agent.policy.ResolvedPolicy;
import com.paiagent.entity.ActionLedger;
import com.paiagent.entity.ApprovalRecord;
import com.paiagent.mapper.ActionLedgerMapper;
import com.paiagent.service.ActionLedgerService;
import com.paiagent.service.ApprovalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class ToolGatewayTest {

    static class FakeTool implements AgentTool {
        private final String name;
        private final AtomicInteger calls = new AtomicInteger();
        private final Map<String, Object> output;
        private final RuntimeException failWith;

        FakeTool(String name, Map<String, Object> output) {
            this(name, output, null);
        }

        FakeTool(String name, Map<String, Object> output, RuntimeException failWith) {
            this.name = name;
            this.output = output;
            this.failWith = failWith;
        }

        @Override public String getName() { return name; }
        @Override public String getDescription() { return "d"; }
        @Override public String getInputSchema() { return "{}"; }

        @Override
        public Map<String, Object> execute(Map<String, Object> args, AgentToolContext ctx) {
            calls.incrementAndGet();
            if (failWith != null) {
                throw failWith;
            }
            return output;
        }
    }

    private ToolExecutionContext ctx(AutonomyLevel allowed, String tenant) {
        return new ToolExecutionContext(tenant, allowed, "react_agent", 1L, 2L, "node-1");
    }

    /** 幂等守卫测试工厂：模拟「健康且为空的 Redis」，行为等价进程内实现。 */
    private IdempotencyGuard guard() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOperations);
        // Mockito 对返回 Boolean 的方法默认给 false（非 null）；不显式打桩会被当成
        // 「Redis 明确拒绝占位」而拒绝首次执行，故模拟 SETNX 成功。
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        return new IdempotencyGuard(600000, redis, new ObjectMapper());
    }

    private ToolGateway gateway(PolicyRegistry policyRegistry, IdempotencyGuard guard,
                                ActionLedgerService ledger, ApprovalService approval) {
        return new ToolGateway(policyRegistry, guard, ledger, approval);
    }

    @Test
    void shouldExecuteAllowedToolAndAudit() throws Exception {
        PolicyRegistry pr = mock(PolicyRegistry.class);
        when(pr.resolve("t1")).thenReturn(new ResolvedPolicy(AutonomyLevel.OBSERVE, false, "global", false, false));
        IdempotencyGuard guard = guard();
        ActionLedgerService ledger = mock(ActionLedgerService.class);
        ApprovalService approval = mock(ApprovalService.class);

        FakeTool tool = new FakeTool("t1", Map.of("result", "ok"));
        ToolGateway gw = gateway(pr, guard, ledger, approval);

        ToolGatewayResult result = gw.execute(tool, Map.of("q", 1), ctx(AutonomyLevel.EXECUTE, "global"), new AgentToolContext(null, Map.of()));

        assertEquals(GatewayStatus.EXECUTED, result.status());
        assertEquals("ok", result.output().get("result"));
        assertEquals(1, tool.calls.get());
        verify(ledger).record(argThat(record -> "EXECUTED".equals(record.getActionStatus())));
    }

    @Test
    void shouldDenyCrossTenantWrite() throws Exception {
        PolicyRegistry pr = mock(PolicyRegistry.class);
        when(pr.resolve("t2")).thenReturn(new ResolvedPolicy(AutonomyLevel.OBSERVE, true, "tenantA", false, false));
        IdempotencyGuard guard = guard();
        ActionLedgerService ledger = mock(ActionLedgerService.class);
        ApprovalService approval = mock(ApprovalService.class);

        FakeTool tool = new FakeTool("t2", Map.of());
        ToolGateway gw = gateway(pr, guard, ledger, approval);

        ToolGatewayResult result = gw.execute(tool, Map.of(), ctx(AutonomyLevel.EXECUTE, "tenantB"), new AgentToolContext(null, Map.of()));

        assertEquals(GatewayStatus.DENIED, result.status());
        assertEquals(0, tool.calls.get());
        verify(ledger).record(argThat(record -> "DENIED".equals(record.getActionStatus())));
        verifyNoInteractions(approval);
    }

    @Test
    void shouldRouteToApprovalWhenLevelExceedsAndEscalate() throws Exception {
        PolicyRegistry pr = mock(PolicyRegistry.class);
        when(pr.resolve("t3")).thenReturn(new ResolvedPolicy(AutonomyLevel.ESCALATE, false, "global", false, false));
        IdempotencyGuard guard = guard();
        ActionLedgerService ledger = mock(ActionLedgerService.class);
        ApprovalService approval = mock(ApprovalService.class);
        when(approval.create(any())).thenAnswer(inv -> {
            ApprovalRecord r = inv.getArgument(0);
            r.setId(42L);
            return r;
        });

        FakeTool tool = new FakeTool("t3", Map.of());
        ToolGateway gw = gateway(pr, guard, ledger, approval);

        ToolGatewayResult result = gw.execute(tool, Map.of(), ctx(AutonomyLevel.EXECUTE, "global"), new AgentToolContext(null, Map.of()));

        assertEquals(GatewayStatus.PENDING_APPROVAL, result.status());
        assertEquals(42L, result.approvalId());
        assertEquals(0, tool.calls.get());
        verify(approval).create(any());
        verify(ledger).record(argThat(record ->
                "PENDING_APPROVAL".equals(record.getActionStatus()) && Long.valueOf(42L).equals(record.getApprovalId())));
    }

    @Test
    void shouldDenyWhenLevelExceedsWithoutApproval() throws Exception {
        PolicyRegistry pr = mock(PolicyRegistry.class);
        when(pr.resolve("t4")).thenReturn(new ResolvedPolicy(AutonomyLevel.DRAFT, true, "global", true, false));
        IdempotencyGuard guard = guard();
        ActionLedgerService ledger = mock(ActionLedgerService.class);
        ApprovalService approval = mock(ApprovalService.class);

        FakeTool tool = new FakeTool("t4", Map.of());
        ToolGateway gw = gateway(pr, guard, ledger, approval);

        ToolGatewayResult result = gw.execute(tool, Map.of(), ctx(AutonomyLevel.OBSERVE, "global"), new AgentToolContext(null, Map.of()));

        assertEquals(GatewayStatus.DENIED, result.status());
        assertEquals(0, tool.calls.get());
        verifyNoInteractions(approval);
    }

    @Test
    void shouldHitIdempotencyCacheOnSecondCall() throws Exception {
        PolicyRegistry pr = mock(PolicyRegistry.class);
        when(pr.resolve("t5")).thenReturn(new ResolvedPolicy(AutonomyLevel.OBSERVE, false, "global", true, false));
        IdempotencyGuard guard = guard();
        ActionLedgerService ledger = mock(ActionLedgerService.class);
        ApprovalService approval = mock(ApprovalService.class);

        FakeTool tool = new FakeTool("t5", Map.of("v", 1));
        ToolGateway gw = gateway(pr, guard, ledger, approval);
        Map<String, Object> args = Map.of("k", "same");

        ToolGatewayResult first = gw.execute(tool, args, ctx(AutonomyLevel.EXECUTE, "global"), new AgentToolContext(null, Map.of()));
        ToolGatewayResult second = gw.execute(tool, args, ctx(AutonomyLevel.EXECUTE, "global"), new AgentToolContext(null, Map.of()));

        assertEquals(GatewayStatus.EXECUTED, first.status());
        assertEquals(GatewayStatus.EXECUTED, second.status());
        assertEquals(1, tool.calls.get()); // 第二次命中缓存，工具只执行一次
    }

    @Test
    void shouldPropagateToolExceptionAndAuditError() {
        PolicyRegistry pr = mock(PolicyRegistry.class);
        when(pr.resolve("t6")).thenReturn(new ResolvedPolicy(AutonomyLevel.OBSERVE, false, "global", false, false));
        IdempotencyGuard guard = guard();
        ActionLedgerService ledger = mock(ActionLedgerService.class);
        ApprovalService approval = mock(ApprovalService.class);

        FakeTool tool = new FakeTool("t6", Map.of(), new RuntimeException("boom"));
        ToolGateway gw = gateway(pr, guard, ledger, approval);

        Exception ex = assertThrows(Exception.class,
                () -> gw.execute(tool, Map.of(), ctx(AutonomyLevel.EXECUTE, "global"), new AgentToolContext(null, Map.of())));
        assertEquals("boom", ex.getMessage());
        verify(ledger).record(argThat(record -> "ERROR".equals(record.getActionStatus())
                && "boom".equals(record.getErrorMessage())));
    }

    // ------------------------------------------------------------------
    // 迭代6/7：预授权穿透（HITL resume 的网关侧行为）
    // 迭代7 A6：判定改为「审批单 + 工具 + 租户」三者匹配
    // ------------------------------------------------------------------

    private ToolExecutionContext preApprovedCtx(Long approvalId) {
        return new ToolExecutionContext("global", AutonomyLevel.EXECUTE, "react_agent",
                1L, 2L, "node-1", null, approvalId);
    }

    @Test
    void shouldExecuteWithApprovedApprovalWithoutCreatingNewOne() throws Exception {
        PolicyRegistry pr = mock(PolicyRegistry.class);
        when(pr.resolve("t7")).thenReturn(new ResolvedPolicy(AutonomyLevel.ESCALATE, false, "global", false, true));
        IdempotencyGuard guard = guard();
        ActionLedgerService ledger = mock(ActionLedgerService.class);
        ApprovalService approval = mock(ApprovalService.class);
        when(approval.isApprovedFor(42L, "t7", "global")).thenReturn(true);

        FakeTool tool = new FakeTool("t7", Map.of("v", "ok"));
        ToolGateway gw = gateway(pr, guard, ledger, approval);

        // 等级越限（ESCALATE > EXECUTE）但携带已获批审批单：直接穿透执行
        ToolGatewayResult result = gw.execute(tool, Map.of(), preApprovedCtx(42L), new AgentToolContext(null, Map.of()));

        assertEquals(GatewayStatus.EXECUTED, result.status());
        assertEquals(1, tool.calls.get());
        // 不新建审批单；审计行关联该审批单
        verify(approval, never()).create(any());
        verify(ledger).record(argThat(record ->
                "EXECUTED".equals(record.getActionStatus()) && Long.valueOf(42L).equals(record.getApprovalId())));
    }

    @Test
    void shouldStillRouteToApprovalWhenCarriedApprovalNotApproved() throws Exception {
        PolicyRegistry pr = mock(PolicyRegistry.class);
        when(pr.resolve("t8")).thenReturn(new ResolvedPolicy(AutonomyLevel.ESCALATE, false, "global", false, true));
        IdempotencyGuard guard = guard();
        ActionLedgerService ledger = mock(ActionLedgerService.class);
        ApprovalService approval = mock(ApprovalService.class);
        // 审批单存在但已被拒绝/尚未获批：不放行，转新审批
        when(approval.isApprovedFor(99L, "t8", "global")).thenReturn(false);
        when(approval.create(any())).thenAnswer(inv -> {
            ApprovalRecord r = inv.getArgument(0);
            r.setId(100L);
            return r;
        });

        FakeTool tool = new FakeTool("t8", Map.of());
        ToolGateway gw = gateway(pr, guard, ledger, approval);

        ToolGatewayResult result = gw.execute(tool, Map.of(), preApprovedCtx(99L), new AgentToolContext(null, Map.of()));

        assertEquals(GatewayStatus.PENDING_APPROVAL, result.status());
        assertEquals(100L, result.approvalId());
        assertEquals(0, tool.calls.get());
        verify(approval).create(any());
    }

    /**
     * 迭代7 A6：预授权必须「审批单 + 工具 + 租户」三者匹配——
     * 网关需把本次调用的工具名与租户交给绑定校验，而不是只查审批状态
     * （修复前任意已批准单号可为任意工具做穿透）。
     */
    @Test
    void shouldPassToolAndTenantToApprovalBindingCheck() throws Exception {
        PolicyRegistry pr = mock(PolicyRegistry.class);
        when(pr.resolve("t10")).thenReturn(new ResolvedPolicy(AutonomyLevel.ESCALATE, false, "global", false, true));
        IdempotencyGuard guard = guard();
        ActionLedgerService ledger = mock(ActionLedgerService.class);
        ApprovalService approval = mock(ApprovalService.class);
        when(approval.isApprovedFor(any(), any(), any())).thenReturn(false);
        when(approval.create(any())).thenAnswer(inv -> {
            ApprovalRecord r = inv.getArgument(0);
            r.setId(200L);
            return r;
        });

        FakeTool tool = new FakeTool("t10", Map.of());
        ToolGateway gw = gateway(pr, guard, ledger, approval);

        ToolGatewayResult result = gw.execute(tool, Map.of(), preApprovedCtx(55L), new AgentToolContext(null, Map.of()));

        // 断言网关把 (审批单, 工具名, 租户) 一并交给绑定校验
        verify(approval).isApprovedFor(55L, "t10", "global");
        assertEquals(GatewayStatus.PENDING_APPROVAL, result.status());
        assertEquals(0, tool.calls.get());
    }

    @Test
    void shouldPersistSuspendContextOnApprovalRecord() throws Exception {
        PolicyRegistry pr = mock(PolicyRegistry.class);
        when(pr.resolve("t9")).thenReturn(new ResolvedPolicy(AutonomyLevel.ESCALATE, false, "global", false, true));
        IdempotencyGuard guard = guard();
        ActionLedgerService ledger = mock(ActionLedgerService.class);
        ApprovalService approval = mock(ApprovalService.class);
        when(approval.create(any())).thenAnswer(inv -> {
            ApprovalRecord r = inv.getArgument(0);
            r.setId(7L);
            return r;
        });

        Map<String, Object> suspendContext = new LinkedHashMap<>();
        suspendContext.put("executionId", 100);
        suspendContext.put("toolName", "t9");
        suspendContext.put("trace", List.of(Map.of("step", 1)));
        ToolExecutionContext callCtx = new ToolExecutionContext(
                "global", AutonomyLevel.EXECUTE, "react_agent", 100L, 200L, "node-1", suspendContext, null);

        FakeTool tool = new FakeTool("t9", Map.of());
        ToolGateway gw = gateway(pr, guard, ledger, approval);
        gw.execute(tool, Map.of(), callCtx, new AgentToolContext(null, Map.of()));

        ArgumentCaptor<ApprovalRecord> captor = ArgumentCaptor.forClass(ApprovalRecord.class);
        verify(approval).create(captor.capture());
        ApprovalRecord created = captor.getValue();
        assertNotNull(created.getSuspendContext(), "挂起快照必须落库");
        assertTrue(created.getSuspendContext().contains("\"toolName\":\"t9\""));
        assertTrue(created.getSuspendContext().contains("\"executionId\":100"));
    }

    // ------------------------------------------------------------------
    // 迭代9 P1-8：requiresApproval 提级 + 幂等原子抢占
    // ------------------------------------------------------------------

    /**
     * P1-8 核心行为：{@code requiresApproval} 是工具自身的显式声明，必须独立于等级比较生效。
     * 修复前整个审批判定被嵌在「等级越限」分支内——等级充足时声明的审批被整段跳过，
     * 标注 requiresApproval 的写工具会直接执行（本用例在修复前会得到 EXECUTED）。
     */
    @Test
    void shouldRouteToApprovalWhenRequiresApprovalEvenIfLevelSufficient() throws Exception {
        PolicyRegistry pr = mock(PolicyRegistry.class);
        // OBSERVE 低于 EXECUTE：等级不越限，但工具显式声明需审批
        when(pr.resolve("t11")).thenReturn(new ResolvedPolicy(AutonomyLevel.OBSERVE, false, "global", false, true));
        IdempotencyGuard guard = guard();
        ActionLedgerService ledger = mock(ActionLedgerService.class);
        ApprovalService approval = mock(ApprovalService.class);
        when(approval.create(any())).thenAnswer(inv -> {
            ApprovalRecord r = inv.getArgument(0);
            r.setId(300L);
            return r;
        });

        FakeTool tool = new FakeTool("t11", Map.of());
        ToolGateway gw = gateway(pr, guard, ledger, approval);

        ToolGatewayResult result = gw.execute(tool, Map.of(), ctx(AutonomyLevel.EXECUTE, "global"),
                new AgentToolContext(null, Map.of()));

        assertEquals(GatewayStatus.PENDING_APPROVAL, result.status());
        assertEquals(300L, result.approvalId());
        assertEquals(0, tool.calls.get(), "声明需审批的工具不得直接执行");
        verify(approval).create(any());
        verify(ledger).record(argThat(record ->
                "PENDING_APPROVAL".equals(record.getActionStatus()) && Long.valueOf(300L).equals(record.getApprovalId())));
    }

    /** requiresApproval 与等级充足并存，且携带已获批审批单：应预授权穿透执行。 */
    @Test
    void shouldExecutePreApprovedWhenRequiresApprovalAndLevelSufficient() throws Exception {
        PolicyRegistry pr = mock(PolicyRegistry.class);
        when(pr.resolve("t12")).thenReturn(new ResolvedPolicy(AutonomyLevel.OBSERVE, false, "global", false, true));
        IdempotencyGuard guard = guard();
        ActionLedgerService ledger = mock(ActionLedgerService.class);
        ApprovalService approval = mock(ApprovalService.class);
        when(approval.isApprovedFor(77L, "t12", "global")).thenReturn(true);

        FakeTool tool = new FakeTool("t12", Map.of("v", "ok"));
        ToolGateway gw = gateway(pr, guard, ledger, approval);

        ToolGatewayResult result = gw.execute(tool, Map.of(), preApprovedCtx(77L),
                new AgentToolContext(null, Map.of()));

        assertEquals(GatewayStatus.EXECUTED, result.status());
        assertEquals(1, tool.calls.get());
        verify(approval, never()).create(any());
        verify(ledger).record(argThat(record ->
                "EXECUTED".equals(record.getActionStatus()) && Long.valueOf(77L).equals(record.getApprovalId())));
    }

    /**
     * P1-8：执行前原子抢占幂等键。未抢到 = 已有并发在途调用，必须拒绝，
     * 而不是沿用「查缓存未命中就执行」的 check-then-act 语义。
     */
    @Test
    void shouldDenyWhenIdempotencyClaimNotAcquired() throws Exception {
        PolicyRegistry pr = mock(PolicyRegistry.class);
        when(pr.resolve("t13")).thenReturn(new ResolvedPolicy(AutonomyLevel.OBSERVE, false, "global", true, false));
        IdempotencyGuard guard = mock(IdempotencyGuard.class);
        when(guard.getIfPresent(any())).thenReturn(null);   // 缓存未命中
        when(guard.tryClaim(any())).thenReturn(false);      // 但已被并发调用抢占
        ActionLedgerService ledger = mock(ActionLedgerService.class);
        ApprovalService approval = mock(ApprovalService.class);

        FakeTool tool = new FakeTool("t13", Map.of());
        ToolGateway gw = gateway(pr, guard, ledger, approval);

        ToolGatewayResult result = gw.execute(tool, Map.of("k", "v"), ctx(AutonomyLevel.EXECUTE, "global"),
                new AgentToolContext(null, Map.of()));

        assertEquals(GatewayStatus.DENIED, result.status());
        assertEquals(0, tool.calls.get(), "未抢到幂等占位时不得执行工具");
        verify(ledger).record(argThat(record -> "DENIED".equals(record.getActionStatus())));
        verify(guard, never()).put(any(), any());
    }

    /** P1-8：幂等工具执行失败必须归还占位，否则 TTL 窗口内的正常重试会被误判为并发。 */
    @Test
    void shouldReleaseIdempotencyClaimWhenToolFails() {
        PolicyRegistry pr = mock(PolicyRegistry.class);
        when(pr.resolve("t14")).thenReturn(new ResolvedPolicy(AutonomyLevel.OBSERVE, false, "global", true, false));
        IdempotencyGuard guard = mock(IdempotencyGuard.class);
        when(guard.getIfPresent(any())).thenReturn(null);
        when(guard.tryClaim(any())).thenReturn(true);
        ActionLedgerService ledger = mock(ActionLedgerService.class);
        ApprovalService approval = mock(ApprovalService.class);

        FakeTool tool = new FakeTool("t14", Map.of(), new RuntimeException("boom"));
        ToolGateway gw = gateway(pr, guard, ledger, approval);

        assertThrows(Exception.class, () -> gw.execute(tool, Map.of("k", "v"),
                ctx(AutonomyLevel.EXECUTE, "global"), new AgentToolContext(null, Map.of())));

        verify(guard).release(any());
        verify(guard, never()).put(any(), any());
        verify(ledger).record(argThat(record -> "ERROR".equals(record.getActionStatus())));
    }
}
