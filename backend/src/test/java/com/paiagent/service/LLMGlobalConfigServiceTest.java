package com.paiagent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.paiagent.common.ApiKeyMasker;
import com.paiagent.entity.LLMGlobalConfig;
import com.paiagent.mapper.LLMGlobalConfigMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迭代7（评审报告 A3 配套）：apiKey 掩码回写保护。
 *
 * <p>「展示脱敏」后前端会把掩码原样回传，保存时必须保留库中原密钥；
 * 新增场景下掩码无原值可恢复，必须显式拒绝而不是把假密钥写进库。
 */
class LLMGlobalConfigServiceTest {

    private LLMGlobalConfigMapper mapper;
    private LLMGlobalConfigService service;

    /**
     * LambdaQueryWrapper/LambdaUpdateWrapper 依赖实体的 TableInfo lambda 缓存；
     * 纯单测无 Spring 容器需显式初始化。
     */
    @BeforeAll
    static void initTableInfoCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, LLMGlobalConfig.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(LLMGlobalConfigMapper.class);
        service = new LLMGlobalConfigService();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        // 无重名配置、非该供应商首个配置、无软删残留
        lenient().when(mapper.selectCount(any())).thenReturn(0L);
        lenient().when(mapper.findAnyByProviderAndConfigName(any(), any())).thenReturn(null);
        lenient().when(mapper.updateById(any())).thenReturn(1);
        lenient().when(mapper.insert(any())).thenReturn(1);
    }

    @Test
    @DisplayName("更新时回传掩码 → 保留库中原密钥，不写入掩码串")
    void keepsStoredApiKeyWhenMaskedValueSubmitted() {
        LLMGlobalConfig stored = config(7L, "sk-real-secret-9999");
        when(mapper.selectById(7L)).thenReturn(stored);

        LLMGlobalConfig submitted = config(7L, ApiKeyMasker.mask("sk-real-secret-9999"));
        assertEquals("sk-****9999", submitted.getApiKey(), "前置条件：提交值确为掩码形态");

        service.saveConfig(submitted);

        ArgumentCaptor<LLMGlobalConfig> captor = ArgumentCaptor.forClass(LLMGlobalConfig.class);
        verify(mapper).updateById(captor.capture());
        assertEquals("sk-real-secret-9999", captor.getValue().getApiKey());
    }

    @Test
    @DisplayName("更新时提交真实新密钥 → 正常覆盖")
    void overwritesApiKeyWhenPlainValueSubmitted() {
        lenient().when(mapper.selectById(7L)).thenReturn(config(7L, "sk-old-secret-0000"));

        LLMGlobalConfig submitted = config(7L, "sk-brand-new-secret-1234");
        service.saveConfig(submitted);

        ArgumentCaptor<LLMGlobalConfig> captor = ArgumentCaptor.forClass(LLMGlobalConfig.class);
        verify(mapper).updateById(captor.capture());
        assertEquals("sk-brand-new-secret-1234", captor.getValue().getApiKey());
        verify(mapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("新增时提交掩码 → 显式拒绝（避免假密钥静默落库）")
    void rejectsMaskedValueOnCreate() {
        LLMGlobalConfig submitted = config(null, "sk-****9999");

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> service.saveConfig(submitted));
        assertEquals("新增配置时 API 密钥不能使用脱敏掩码，请填写真实密钥", error.getMessage());
        verify(mapper, never()).insert(any());
    }

    private LLMGlobalConfig config(Long id, String apiKey) {
        LLMGlobalConfig config = new LLMGlobalConfig();
        config.setId(id);
        config.setProvider("openai");
        config.setConfigName("默认");
        config.setApiUrl("https://api.example.com/v1");
        config.setApiKey(apiKey);
        config.setModel("gpt-4o-mini");
        config.setIsDefault(0);
        return config;
    }
}
