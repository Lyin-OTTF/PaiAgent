package com.paiagent.controller;

import com.paiagent.common.GlobalExceptionHandler;
import com.paiagent.dto.WorkflowResponse;
import com.paiagent.service.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 迭代7（评审报告 P2 #11）：Controller 层首个 MockMvc 测试。
 *
 * <p>用 {@code standaloneSetup}（不加载 Spring 上下文，无需 DB/Redis），
 * 锁定「统一 {@link com.paiagent.common.Result} 响应结构 + 全局异常处理」这条此前完全无测试保护的链路：
 * 越权（Service 抛参数异常）→ 400、校验失败 → 400、未知异常 → 500 且不泄露细节。
 */
class WorkflowControllerTest {

    private WorkflowService workflowService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        workflowService = mock(WorkflowService.class);
        WorkflowController controller = new WorkflowController();
        ReflectionTestUtils.setField(controller, "workflowService", workflowService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("列表接口返回统一 Result 结构")
    void listReturnsUnifiedResult() throws Exception {
        WorkflowResponse response = new WorkflowResponse();
        response.setId(1L);
        response.setName("demo");
        when(workflowService.listWorkflows()).thenReturn(List.of(response));

        mockMvc.perform(get("/api/workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].name").value("demo"));
    }

    @Test
    @DisplayName("越权访问（Service 抛参数异常）→ 400 统一结构")
    void ownershipViolationMapsToBadRequest() throws Exception {
        when(workflowService.getWorkflowById(9L))
                .thenThrow(new IllegalArgumentException("工作流不存在或无权访问"));

        mockMvc.perform(get("/api/workflows/9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("工作流不存在或无权访问"));
    }

    @Test
    @DisplayName("校验失败（@Valid 空名称）→ 400")
    void validationFailureMapsToBadRequest() throws Exception {
        mockMvc.perform(post("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"flowData\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("未预期异常 → 500 且只回显通用文案")
    void unexpectedFailureMapsToInternalError() throws Exception {
        when(workflowService.listWorkflows())
                .thenThrow(new RuntimeException("jdbc:mysql://10.0.0.5?password=secret"));

        mockMvc.perform(get("/api/workflows"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("服务内部错误，请稍后重试"));
    }

    @Test
    @DisplayName("删除接口返回统一结构（归属校验由 Service 层保证）")
    void deleteReturnsSuccess() throws Exception {
        mockMvc.perform(delete("/api/workflows/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
