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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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

    /** 幂等守卫测试工厂：Redis mock 默认未命中/无操作，行为等价进程内实现。 */
    private IdempotencyGuard guard() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
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
}
