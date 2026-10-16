package com.paiagent.controller;

import com.paiagent.common.GlobalExceptionHandler;
import com.paiagent.service.KnowledgeBaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 迭代8（评审报告 P2 #11）：{@link KnowledgeBaseController} MockMvc 测试。
 *
 * <p>锁定迭代7 A2 归属隔离在 HTTP 边界上的行为：越权（Service 抛参数异常）→ 400 统一结构；
 * 参数校验失败 → 400；正常路径走统一 Result 信封。
 */
class KnowledgeBaseControllerTest {

    private KnowledgeBaseService knowledgeBaseService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new KnowledgeBaseController(knowledgeBaseService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("列表接口：返回统一 Result 结构")
    void listReturnsUnifiedResult() throws Exception {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("id", 1);
        base.put("name", "产品文档库");
        when(knowledgeBaseService.listKnowledgeBases()).thenReturn(List.of(base));

        mockMvc.perform(get("/api/knowledge-bases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].name").value("产品文档库"));
    }

    @Test
    @DisplayName("越权访问（Service 抛参数异常）→ 400 统一结构")
    void ownershipViolationMapsToBadRequest() throws Exception {
        when(knowledgeBaseService.getKnowledgeBase(9L))
                .thenThrow(new IllegalArgumentException("知识库不存在或无权访问"));

        mockMvc.perform(get("/api/knowledge-bases/9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("知识库不存在或无权访问"));
    }

    @Test
    @DisplayName("创建接口：名称为空（@Valid）→ 400")
    void createValidationFailureMapsToBadRequest() throws Exception {
        mockMvc.perform(post("/api/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"description\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("创建接口：合法请求委托 Service 并回显数据")
    void createDelegatesToService() throws Exception {
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("id", 5);
        created.put("name", "新知识库");
        when(knowledgeBaseService.createKnowledgeBase(any())).thenReturn(created);

        mockMvc.perform(post("/api/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新知识库\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("新知识库"));

        ArgumentCaptor<com.paiagent.dto.KnowledgeBaseRequest> captor =
                ArgumentCaptor.forClass(com.paiagent.dto.KnowledgeBaseRequest.class);
        verify(knowledgeBaseService).createKnowledgeBase(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("新知识库", captor.getValue().getName());
    }

    @Test
    @DisplayName("删除接口：委托 Service 并返回统一结构")
    void deleteDelegatesToService() throws Exception {
        mockMvc.perform(delete("/api/knowledge-bases/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(knowledgeBaseService).deleteKnowledgeBase(1L);
    }

    @Test
    @DisplayName("检索接口：Service 抛参异常 → 400")
    void searchOwnershipViolationMapsToBadRequest() throws Exception {
        when(knowledgeBaseService.search(org.mockito.ArgumentMatchers.eq(9L), any()))
                .thenThrow(new IllegalArgumentException("知识库不存在或无权访问"));

        mockMvc.perform(post("/api/knowledge-bases/9/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"关键词\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
