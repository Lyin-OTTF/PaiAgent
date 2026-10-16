package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.paiagent.engine.WorkflowConfigParser;
import com.paiagent.engine.agent.AgentTool;
import com.paiagent.engine.agent.AgentToolContext;
import com.paiagent.engine.agent.AgentToolRegistry;
import com.paiagent.engine.agent.gateway.IdempotencyGuard;
import com.paiagent.engine.agent.gateway.ToolGateway;
import com.paiagent.engine.agent.hitl.HitlSuspendContext;
import com.paiagent.engine.agent.policy.AutonomyLevel;
import com.paiagent.engine.agent.policy.PolicyRegistry;
import com.paiagent.engine.agent.policy.ResolvedPolicy;
import com.paiagent.engine.executor.impl.ReActAgentNodeExecutor;
import com.paiagent.entity.ActionLedger;
import com.paiagent.entity.ApprovalRecord;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.ApprovalRecordMapper;
import com.paiagent.mapper.ExecutionRecordMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 迭代6：HITL 审批 resume 闭环测试（HitlResumeService 编排 + ToolGateway 预授权穿透）。
 *
 * <p>关键不变量：
 * <ul>
 *   <li>审批通过后，被挂起的工具调用恰好执行一次（恢复不重放、不双跑）；</li>
 *   <li>执行记录被回写：nodeResults 中该节点 output 与 outputData 更新为补执行产物；</li>
 *   <li>审计链完整：EXECUTED 账本行关联审批单号；</li>
 *   <li>异常路径全部可观测：SKIPPED（无快照）/ FAILED（工作流丢失、工具丢失、结果缺失等）。</li>
 * </ul>
 */
class HitlResumeServiceTest {

    /**
     * 迭代7 B1：补执行前会用 LambdaUpdateWrapper 原子抢占执行权；
     * 纯单元测试没有 Spring/MyBatis 容器，需手动初始化实体的 lambda 缓存。
     */
    @BeforeAll
    static void initTableInfoCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ApprovalRecord.class);
    }

    private static final Long APPROVAL_ID = 42L;
    private static final Long EXECUTION_ID = 100L;
    private static final Long WORKFLOW_ID = 200L;

    static class FakeTool implements AgentTool {
        private final String name;
        private final AtomicInteger calls = new AtomicInteger();
        private final Map<String, Object> output;

        FakeTool(String name, Map<String, Object> output) {
            this.name = name;
            this.output = output;
        }

        @Override public String getName() { return name; }
        @Override public String getDescription() { return "d"; }
        @Override public String getInputSchema() { return "{}"; }

        @Override
        public Map<String, Object> execute(Map<String, Object> args, AgentToolContext ctx) {
            calls.incrementAndGet();
            return output;
        }
    }

    private FakeTool writeTool;
    private ApprovalRecordMapper approvalMapper;
    private ExecutionRecordMapper executionMapper;
    private WorkflowService workflowService;
    private ActionLedgerService ledgerService;
    private ApprovalService approvalService;
    private ToolGateway toolGateway;
    private ReActAgentNodeExecutor reactExecutor;
    private HitlResumeService service;

    private static final String FLOW_DATA = """
            {"nodes":[{"id":"react-1","type":"react_agent","data":{"type":"react_agent","maxSteps":5,
            "provider":"openai","apiUrl":"https://api.example.com","apiKey":"k","model":"m",
            "prompt":"汇总数据","autonomyLevel":"EXECUTE","tools":["write_tool"]}}],"edges":[]}
            """;

    @BeforeEach
    void setUp() throws Exception {
        writeTool = new FakeTool("write_tool", new LinkedHashMap<>(Map.of("saved", true)));

        approvalMapper = mock(ApprovalRecordMapper.class);
        executionMapper = mock(ExecutionRecordMapper.class);
        workflowService = mock(WorkflowService.class);
        ledgerService = mock(ActionLedgerService.class);

        // 迭代7 B1：补执行前会原子抢占执行权（resumeStatus: NULL → RESUMING），
        // 这里让条件更新恒成功 = 本次抢到执行权；「已被处理」的用例在抢占前就会返回。
        lenient().when(approvalMapper.update(any(), any())).thenReturn(1);

        // 真实审批服务（isApproved 判定来源）+ 真实网关 + 真实幂等守卫（Redis mock 等价进程内）
        approvalService = new ApprovalService(approvalMapper, ledgerService);
        PolicyRegistry policyRegistry = mock(PolicyRegistry.class);
        when(policyRegistry.resolve(eq("write_tool")))
                .thenReturn(new ResolvedPolicy(AutonomyLevel.ESCALATE, false, "global", false, true));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        IdempotencyGuard guard = new IdempotencyGuard(600000, redis, new ObjectMapper());
        toolGateway = new ToolGateway(policyRegistry, guard, ledgerService, approvalService);

        reactExecutor = mock(ReActAgentNodeExecutor.class);
        when(reactExecutor.resumeAfterApproval(any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("output", "周报已完成");
                    out.put("resumedFromApproval", true);
                    return out;
                });

        service = new HitlResumeService(approvalMapper, executionMapper, workflowService,
                new WorkflowConfigParser(), new AgentToolRegistry(List.of(writeTool)),
                toolGateway, reactExecutor);
    }

    private String suspendContextJson() {
        HitlSuspendContext snapshot = new HitlSuspendContext(
                EXECUTION_ID, WORKFLOW_ID, "react-1", "tenantA", AutonomyLevel.EXECUTE, "react_agent",
                "write_tool", new LinkedHashMap<>(Map.of("title", "周报")),
                "请汇总本周数据",
                new LinkedHashMap<>(Map.of("input", "跑一下周报", "executionId", EXECUTION_ID)),
                List.of(), 2, 5);
        return JSON.toJSONString(snapshot.toContextMap());
    }

    private ApprovalRecord approvedApproval() {
        ApprovalRecord approval = new ApprovalRecord();
        approval.setId(APPROVAL_ID);
        approval.setStatus("APPROVED");
        approval.setToolName("write_tool");
        approval.setTenantId("tenantA");
        approval.setSuspendContext(suspendContextJson());
        return approval;
    }

    private ExecutionRecord executionWithResults() {
        ExecutionRecord record = new ExecutionRecord();
        record.setId(EXECUTION_ID);
        record.setStatus("SUCCESS");
        record.setNodeResults("[{\"nodeId\":\"react-1\",\"nodeName\":\"react_agent\",\"status\":\"SUCCESS\","
                + "\"output\":\"{\\\"stopReason\\\":\\\"approval_required\\\"}\"}]");
        return record;
    }

    private void stubHappyPath() {
        when(approvalMapper.selectById(APPROVAL_ID)).thenReturn(approvedApproval());
        when(executionMapper.selectById(EXECUTION_ID)).thenReturn(executionWithResults());
        Workflow workflow = new Workflow();
        workflow.setId(WORKFLOW_ID);
        workflow.setFlowData(FLOW_DATA);
        when(workflowService.getById(WORKFLOW_ID)).thenReturn(workflow);
    }

    @Test
    @DisplayName("审批通过后：工具恰好补执行一次、预授权穿透、执行记录回写、审计关联审批单")
    void shouldResumeApprovedToolExactlyOnceAndAmendRecord() throws Exception {
        stubHappyPath();

        service.doResume(APPROVAL_ID);

        // 1. 被挂起的工具调用恰好执行一次（恢复不重放）
        assertEquals(1, writeTool.calls.get());

        // 2. 预授权穿透：网关未新建审批单（insert 零调用），凭已获批单据执行
        verify(approvalMapper, never()).insert(any());

        // 3. 审计链：EXECUTED 账本行关联该审批单
        verify(ledgerService).record(argThat(ledger ->
                "EXECUTED".equals(ledger.getActionStatus())
                        && Long.valueOf(APPROVAL_ID).equals(ledger.getApprovalId())
                        && "write_tool".equals(ledger.getToolName())));

        // 4. ReAct 续跑被调用，且传入补执行产物
        verify(reactExecutor).resumeAfterApproval(any(), any(), eq(new LinkedHashMap<>(Map.of("saved", true))),
                eq(APPROVAL_ID));

        // 5. 执行记录回写：nodeResults 中该节点 output 与 outputData 均为补执行产物
        verify(executionMapper).updateById(argThat(record -> {
            try {
                List<Object> results = JSON.parseArray(record.getNodeResults());
                Map<?, ?> node = (Map<?, ?>) results.get(0);
                return "react-1".equals(node.get("nodeId"))
                        && String.valueOf(node.get("output")).contains("周报已完成")
                        && String.valueOf(node.get("output")).contains("resumedFromApproval")
                        && record.getOutputData().contains("周报已完成");
            } catch (Exception e) {
                return false;
            }
        }));

        // 6. 补执行状态落库
        verify(approvalMapper).updateById(argThat(r -> "SUCCEEDED".equals(r.getResumeStatus())));
    }

    @Test
    @DisplayName("无挂起上下文（历史/非 ReAct 审批）：标记 SKIPPED，不执行任何工具")
    void shouldSkipWhenNoSuspendContext() throws Exception {
        ApprovalRecord approval = approvedApproval();
        approval.setSuspendContext(null);
        when(approvalMapper.selectById(APPROVAL_ID)).thenReturn(approval);

        service.doResume(APPROVAL_ID);

        assertEquals(0, writeTool.calls.get());
        verify(approvalMapper).updateById(argThat(r -> "SKIPPED".equals(r.getResumeStatus())));
        verify(reactExecutor, never()).resumeAfterApproval(any(), any(), any(), any());
        verify(executionMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("重复恢复：resumeStatus 已处理的记录直接跳过（幂等）")
    void shouldNotResumeTwice() {
        stubHappyPath();
        ApprovalRecord done = approvedApproval();
        done.setResumeStatus("SUCCEEDED");
        when(approvalMapper.selectById(APPROVAL_ID)).thenReturn(done);

        service.doResume(APPROVAL_ID);

        assertEquals(0, writeTool.calls.get());
        verify(approvalMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("迭代7 B1：抢占失败（已被其它执行者处理）时不重复补执行")
    void shouldSkipWhenResumeClaimFails() {
        stubHappyPath();
        // 条件更新影响行数 0 = 抢占失败，说明 resumeStatus 已被别处置位
        when(approvalMapper.update(any(), any())).thenReturn(0);
        when(approvalMapper.selectById(APPROVAL_ID)).thenReturn(approvedApproval());

        service.doResume(APPROVAL_ID);

        assertEquals(0, writeTool.calls.get(), "抢占失败必须跳过，避免重复产生副作用");
        verify(approvalMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("工作流已删除：恢复失败且原因可观测")
    void shouldFailWhenWorkflowMissing() {
        when(approvalMapper.selectById(APPROVAL_ID)).thenReturn(approvedApproval());
        when(workflowService.getById(WORKFLOW_ID)).thenReturn(null);

        service.doResume(APPROVAL_ID);

        assertEquals(0, writeTool.calls.get());
        verify(approvalMapper).updateById(argThat(r ->
                "FAILED".equals(r.getResumeStatus()) && r.getResumeError().contains("工作流不存在")));
    }

    @Test
    @DisplayName("工具已注销：恢复失败且原因可观测")
    void shouldFailWhenToolNotRegistered() {
        when(approvalMapper.selectById(APPROVAL_ID)).thenReturn(approvedApproval());
        Workflow workflow = new Workflow();
        workflow.setId(WORKFLOW_ID);
        workflow.setFlowData(FLOW_DATA);
        when(workflowService.getById(WORKFLOW_ID)).thenReturn(workflow);

        // 用一个不含 write_tool 的注册表构造服务
        HitlResumeService other = new HitlResumeService(approvalMapper, executionMapper, workflowService,
                new WorkflowConfigParser(), new AgentToolRegistry(List.of()),
                toolGateway, reactExecutor);
        other.doResume(APPROVAL_ID);

        assertEquals(0, writeTool.calls.get());
        verify(approvalMapper).updateById(argThat(r ->
                "FAILED".equals(r.getResumeStatus()) && r.getResumeError().contains("不支持的 Agent 工具")));
    }

    @Test
    @DisplayName("执行记录无节点结果：恢复失败（无可回写目标，不静默吞掉）")
    void shouldFailWhenNodeResultsMissing() {
        stubHappyPath();
        ExecutionRecord empty = executionWithResults();
        empty.setNodeResults(null);
        when(executionMapper.selectById(EXECUTION_ID)).thenReturn(empty);

        service.doResume(APPROVAL_ID);

        // 工具已补执行（副作用已发生），但回写失败必须显式 FAILED
        assertEquals(1, writeTool.calls.get());
        verify(approvalMapper).updateById(argThat(r ->
                "FAILED".equals(r.getResumeStatus()) && r.getResumeError().contains("无节点结果")));
    }

    @Test
    @DisplayName("amendNodeResults：只替换目标节点 output，其余条目原样保留")
    void amendNodeResultsReplacesOnlyTargetNode() {
        String json = "[{\"nodeId\":\"start\",\"output\":\"{\\\"a\\\":1}\"},"
                + "{\"nodeId\":\"react-1\",\"output\":\"{\\\"stopReason\\\":\\\"approval_required\\\"}\"},"
                + "{\"nodeId\":\"end\",\"output\":\"{\\\"b\\\":2}\"}]";

        String amended = HitlResumeService.amendNodeResults(json, "react-1",
                new LinkedHashMap<>(Map.of("output", "done")));

        List<Object> results = JSON.parseArray(amended);
        assertEquals(3, results.size());
        assertEquals("{\"a\":1}", ((Map<?, ?>) results.get(0)).get("output"));
        assertTrue(String.valueOf(((Map<?, ?>) results.get(1)).get("output")).contains("done"));
        assertEquals("{\"b\":2}", ((Map<?, ?>) results.get(2)).get("output"));
    }

    @Test
    @DisplayName("amendNodeResults：目标节点缺席时抛错（调用方转为 FAILED）")
    void amendNodeResultsThrowsWhenNodeAbsent() {
        assertThrows(IllegalStateException.class, () ->
                HitlResumeService.amendNodeResults("[{\"nodeId\":\"start\"}]", "react-1", Map.of()));
    }

    @Test
    @DisplayName("异步入口：resumeAsync 后台跑完闭环（轮询等待终态）")
    void resumeAsyncCompletesInBackground() throws Exception {
        stubHappyPath();

        service.resumeAsync(approvedApproval());

        ApprovalRecord seen = null;
        for (int i = 0; i < 50; i++) {
            seen = approvalMapper.selectById(APPROVAL_ID);
            if (seen != null && seen.getResumeStatus() != null) {
                break;
            }
            Thread.sleep(100);
        }
        assertNotNull(seen);
        assertEquals("SUCCEEDED", seen.getResumeStatus());
        assertEquals(1, writeTool.calls.get());
    }
}
