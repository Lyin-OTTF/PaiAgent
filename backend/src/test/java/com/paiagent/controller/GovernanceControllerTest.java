package com.paiagent.controller;

import com.paiagent.common.GlobalExceptionHandler;
import com.paiagent.common.UserContext;
import com.paiagent.dto.ApprovalDecisionRequest;
import com.paiagent.engine.agent.policy.PolicyRegistry;
import com.paiagent.entity.ApprovalRecord;
import com.paiagent.service.ActionLedgerService;
import com.paiagent.service.ApprovalService;
import com.paiagent.service.HitlResumeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 迭代8（评审报告 P2 #11）：{@link GovernanceController} MockMvc 测试。
 *
 * <p>核心锁定迭代7 A7：审批人必须取服务端会话身份（{@link UserContext}），
 * 请求体里的 {@code approver} 字段一律忽略——审计链上的「谁批的」不可伪造。
 */
class GovernanceControllerTest {

    private ApprovalService approvalService;
    private ActionLedgerService actionLedgerService;
    private PolicyRegistry policyRegistry;
    private HitlResumeService hitlResumeService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        approvalService = mock(ApprovalService.class);
        actionLedgerService = mock(ActionLedgerService.class);
        policyRegistry = mock(PolicyRegistry.class);
        hitlResumeService = mock(HitlResumeService.class);
        GovernanceController controller = new GovernanceController(
                approvalService, actionLedgerService, policyRegistry, hitlResumeService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static ApprovalRecord approval(Long id) {
        ApprovalRecord record = new ApprovalRecord();
        record.setId(id);
        return record;
    }

    @Test
    @DisplayName("审批通过：approver 取会话身份，请求体里的 approver 被忽略（A7）")
    void approveUsesSessionIdentityNotRequestBody() throws Exception {
        UserContext.setCurrent("alice");
        ApprovalRecord approved = approval(1L);
        when(approvalService.approve(1L, "alice", "同意")).thenReturn(approved);

        mockMvc.perform(post("/api/governance/approvals/1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"mallory\",\"note\":\"同意\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1));

        // 关键断言：服务端收到的是会话用户，而非请求体伪造值
        verify(approvalService).approve(1L, "alice", "同意");
        // 审批通过即触发补执行（异步）
        verify(hitlResumeService).resumeAsync(approved);
    }

    @Test
    @DisplayName("审批通过：无会话身份时回落 system")
    void approveWithoutSessionFallsBackToSystem() throws Exception {
        when(approvalService.approve(7L, "system", null)).thenReturn(approval(7L));

        mockMvc.perform(post("/api/governance/approvals/7/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(approvalService).approve(7L, "system", null);
    }

    @Test
    @DisplayName("审批拒绝：同样以会话身份作为审批人")
    void rejectUsesSessionIdentity() throws Exception {
        UserContext.setCurrent("bob");
        when(approvalService.reject(2L, "bob", "不行")).thenReturn(approval(2L));

        mockMvc.perform(post("/api/governance/approvals/2/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"mallory\",\"note\":\"不行\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(approvalService).reject(2L, "bob", "不行");
        // 拒绝不触发补执行
        verify(hitlResumeService, org.mockito.Mockito.never()).resumeAsync(any());
    }

    @Test
    @DisplayName("审批并发冲突（IllegalStateException）→ 错误信封")
    void approveConflictReturnsErrorEnvelope() throws Exception {
        UserContext.setCurrent("alice");
        when(approvalService.approve(anyLong(), anyString(), any()))
                .thenThrow(new IllegalStateException("审批已被其他人处理，请刷新后重试: 1"));

        mockMvc.perform(post("/api/governance/approvals/1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"同意\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("审批已被其他人处理，请刷新后重试: 1"));
    }

    @Test
    @DisplayName("审批列表：status 过滤参数透传")
    void listApprovalsPassesStatusFilter() throws Exception {
        when(approvalService.listByStatus("PENDING")).thenReturn(List.of(approval(1L), approval(2L)));

        mockMvc.perform(get("/api/governance/approvals").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2));

        verify(approvalService).listByStatus("PENDING");
    }

    @Test
    @DisplayName("审批决策请求 DTO 仍保留 approver 字段（向后兼容），但不被服务端采信")
    void decisionRequestStillCarriesApproverForCompatibility() {
        // 编译期契约：DTO 字段仍在，控制器不读取——防回归时误删字段破坏旧前端
        ApprovalDecisionRequest request = new ApprovalDecisionRequest();
        request.setApprover("legacy-value");
        request.setNote("note");
        org.junit.jupiter.api.Assertions.assertEquals("legacy-value", request.getApprover());
    }
}
