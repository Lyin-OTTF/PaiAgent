package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.paiagent.dto.McpToolConfigRequest;
import com.paiagent.entity.McpToolConfig;
import com.paiagent.mapper.McpToolConfigMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迭代7（评审报告 A3 配套）：MCP 工具配置的密钥环境变量回写保护。
 *
 * <p>下发时密钥类环境变量被打码为 {@code ******}；前端未修改会原样回传，
 * 保存时必须恢复库中原值，否则真实密钥会被掩码串静默覆盖。
 */
class McpToolConfigServiceTest {

    private McpToolConfigMapper mapper;
    private McpToolConfigService service;

    @BeforeAll
    static void initTableInfoCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, McpToolConfig.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(McpToolConfigMapper.class);
        service = new McpToolConfigService(mock(SearchInfinityMcpClient.class));
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        lenient().when(mapper.updateById(any())).thenReturn(1);
    }

    @Test
    @DisplayName("更新时回传 ****** → 保留库中原密钥")
    void keepsStoredSecretWhenMaskedValueSubmitted() {
        McpToolConfig stored = storedConfig("real-search-key-123");
        when(mapper.selectById(5L)).thenReturn(stored);

        McpToolConfigRequest request = request(Map.of("ASK_ECHO_SEARCH_INFINITY_API_KEY", "******"));
        service.updateConfig(5L, request);

        String envJson = capturedEnvJson();
        assertTrue(envJson.contains("real-search-key-123"), "应保留库中原密钥");
        assertFalse(envJson.contains("******"), "不得把掩码串写进库");
    }

    @Test
    @DisplayName("更新时提交真实新密钥 → 正常覆盖")
    void overwritesSecretWhenPlainValueSubmitted() {
        when(mapper.selectById(5L)).thenReturn(storedConfig("real-search-key-123"));

        service.updateConfig(5L, request(Map.of("ASK_ECHO_SEARCH_INFINITY_API_KEY", "brand-new-key-456")));

        assertTrue(capturedEnvJson().contains("brand-new-key-456"));
    }

    @Test
    @DisplayName("非密钥类环境变量的 ****** 按用户输入原样保留（不误判）")
    void doesNotTouchNonSecretPlaceholder() {
        McpToolConfig stored = new McpToolConfig();
        stored.setId(5L);
        stored.setEnv(JSON.toJSONString(Map.of("LOG_LEVEL", "info")));
        when(mapper.selectById(5L)).thenReturn(stored);

        service.updateConfig(5L, request(Map.of("LOG_LEVEL", "******")));

        assertTrue(capturedEnvJson().contains("******"), "非密钥键不参与恢复");
    }

    private String capturedEnvJson() {
        ArgumentCaptor<McpToolConfig> captor = ArgumentCaptor.forClass(McpToolConfig.class);
        verify(mapper).updateById(captor.capture());
        return captor.getValue().getEnv();
    }

    private McpToolConfig storedConfig(String apiKey) {
        McpToolConfig stored = new McpToolConfig();
        stored.setId(5L);
        stored.setEnv(JSON.toJSONString(Map.of("ASK_ECHO_SEARCH_INFINITY_API_KEY", apiKey)));
        return stored;
    }

    private McpToolConfigRequest request(Map<String, String> env) {
        McpToolConfigRequest request = new McpToolConfigRequest();
        request.setName("联网搜索");
        request.setToolName("web_search");
        request.setToolType("agent_plan_web_search");
        request.setTransport("stdio");
        request.setCommand("mcp-server-askecho-search-infinity");
        request.setArgs(List.of("--from", "pkg"));
        request.setEnv(env);
        request.setEnabled(1);
        return request;
    }
}
