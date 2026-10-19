package com.paiagent.controller;

import com.paiagent.common.GlobalExceptionHandler;
import com.paiagent.entity.LLMGlobalConfig;
import com.paiagent.service.LLMGlobalConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 迭代8（评审报告 P2 #11）：{@link LLMConfigController} MockMvc 测试。
 *
 * <p>锁定迭代7 A3 的密钥防线在 HTTP 边界上的行为：所有出站响应的 apiKey 必须打码；
 * 保存失败的三种形态（参数非法 / 别名重复 / 未知异常）都收敛为统一错误信封且不回显堆栈。
 */
class LLMConfigControllerTest {

    private LLMGlobalConfigService llmGlobalConfigService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        llmGlobalConfigService = mock(LLMGlobalConfigService.class);
        LLMConfigController controller = new LLMConfigController();
        ReflectionTestUtils.setField(controller, "llmGlobalConfigService", llmGlobalConfigService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static LLMGlobalConfig config(Long id, String apiKey) {
        LLMGlobalConfig config = new LLMGlobalConfig();
        config.setId(id);
        config.setProvider("openai");
        config.setConfigName("默认配置");
        config.setApiUrl("https://api.openai.com/v1");
        config.setApiKey(apiKey);
        config.setModel("gpt-4o-mini");
        return config;
    }

    @Test
    @DisplayName("列表接口：apiKey 打码下发，其余字段原样返回")
    void listAllMasksApiKey() throws Exception {
        when(llmGlobalConfigService.list()).thenReturn(List.of(config(1L, "sk-abcdefghijklmn")));

        mockMvc.perform(get("/api/llm-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].apiKey").value("sk-****klmn"))
                .andExpect(jsonPath("$.data[0].model").value("gpt-4o-mini"));
    }

    @Test
    @DisplayName("详情接口：apiKey 打码下发")
    void getByIdMasksApiKey() throws Exception {
        when(llmGlobalConfigService.getById(1L)).thenReturn(config(1L, "sk-abcdefghijklmn"));

        mockMvc.perform(get("/api/llm-config/detail/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKey").value("sk-****klmn"));
    }

    @Test
    @DisplayName("详情接口：配置不存在 → 错误信封（HTTP 200 + code 500）")
    void getByIdMissingReturnsErrorEnvelope() throws Exception {
        when(llmGlobalConfigService.getById(99L)).thenReturn(null);

        mockMvc.perform(get("/api/llm-config/detail/99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("配置不存在"));
    }

    @Test
    @DisplayName("保存接口：请求中的明文密钥原样交予 Service，回显仍打码")
    void saveConfigPassesRawKeyAndMasksEcho() throws Exception {
        when(llmGlobalConfigService.saveConfig(any())).thenReturn(config(2L, "sk-abcdefghijklmn"));

        mockMvc.perform(post("/api/llm-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"openai\",\"configName\":\"默认配置\","
                                + "\"apiUrl\":\"https://api.openai.com/v1\",\"apiKey\":\"sk-newkey123456789\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.apiKey").value("sk-****klmn"));

        ArgumentCaptor<LLMGlobalConfig> captor = ArgumentCaptor.forClass(LLMGlobalConfig.class);
        verify(llmGlobalConfigService).saveConfig(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                "sk-newkey123456789", captor.getValue().getApiKey(),
                "Controller 不应改写请求密钥（掩码回写保护在 Service 层）");
    }

    @Test
    @DisplayName("保存接口：新增时回传掩码值 → 参数错误信封（迭代8 补强 A3）")
    void saveConfigRejectsMaskedKey() throws Exception {
        when(llmGlobalConfigService.saveConfig(any()))
                .thenThrow(new IllegalArgumentException("新增配置的 apiKey 不能是掩码值，请填写真实密钥"));

        mockMvc.perform(post("/api/llm-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"openai\",\"apiKey\":\"sk-****klmn\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("保存配置失败: 新增配置的 apiKey 不能是掩码值，请填写真实密钥"));
    }

    @Test
    @DisplayName("保存接口：别名重复 → DuplicateKeyException 专属文案")
    void saveConfigDuplicateAlias() throws Exception {
        when(llmGlobalConfigService.saveConfig(any())).thenThrow(new DuplicateKeyException("dup"));

        mockMvc.perform(post("/api/llm-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"openai\",\"apiKey\":\"sk-real\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("保存配置失败: 同一供应商下的配置别名不能重复"));
    }

    @Test
    @DisplayName("删除接口：委托 Service 并返回统一结构")
    void deleteConfigDelegates() throws Exception {
        mockMvc.perform(delete("/api/llm-config/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(llmGlobalConfigService).deleteConfig(3L);
    }

    @Test
    @DisplayName("设置默认接口：失败时错误信封带原因，不回显堆栈")
    void setDefaultConfigFailureEnvelope() throws Exception {
        // 迭代10：服务层「配置不存在」为业务校验异常（IllegalArgumentException），
        // 控制器保留其可读消息；未预期异常才收敛为通用文案
        doThrow(new IllegalArgumentException("配置不存在")).when(llmGlobalConfigService).setDefaultConfig(9L);

        mockMvc.perform(post("/api/llm-config/9/default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("设置默认配置失败: 配置不存在"));
    }
}
