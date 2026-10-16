package com.paiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.paiagent.common.ApiKeyMasker;
import com.paiagent.entity.LLMGlobalConfig;
import com.paiagent.mapper.LLMGlobalConfigMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.List;

/**
 * 全局模型配置服务，LLM 与 TTS 节点共用。
 */
@Service
public class LLMGlobalConfigService extends ServiceImpl<LLMGlobalConfigMapper, LLMGlobalConfig> {

    /**
     * 获取某提供商的所有配置
     */
    public List<LLMGlobalConfig> listByProvider(String provider) {
        LambdaQueryWrapper<LLMGlobalConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LLMGlobalConfig::getProvider, provider)
               .orderByDesc(LLMGlobalConfig::getIsDefault)
               .orderByDesc(LLMGlobalConfig::getUpdatedAt);
        return this.list(wrapper);
    }

    /**
     * 获取某提供商的默认配置
     */
    public LLMGlobalConfig getDefaultConfig(String provider) {
        LambdaQueryWrapper<LLMGlobalConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LLMGlobalConfig::getProvider, provider)
               .eq(LLMGlobalConfig::getIsDefault, 1);
        return this.getOne(wrapper);
    }

    /**
     * 设置默认配置
     * 先清除该提供商的其他默认配置，再设置指定配置为默认
     */
    @Transactional
    public void setDefaultConfig(Long id) {
        LLMGlobalConfig config = this.getById(id);
        if (config == null) {
            throw new RuntimeException("配置不存在");
        }

        // 清除该提供商的其他默认配置
        LambdaUpdateWrapper<LLMGlobalConfig> clearWrapper = new LambdaUpdateWrapper<>();
        clearWrapper.eq(LLMGlobalConfig::getProvider, config.getProvider())
                    .set(LLMGlobalConfig::getIsDefault, 0);
        this.update(clearWrapper);

        // 设置指定配置为默认
        config.setIsDefault(1);
        this.updateById(config);
    }

    /**
     * 保存配置（新增或更新）
     * 如果是第一个配置，自动设置为默认
     */
    @Transactional
    public LLMGlobalConfig saveConfig(LLMGlobalConfig config) {
        // 迭代7 A3：前端拿到的是打码后的 apiKey，未修改密钥时会原样回传掩码；
        // 识别该情形并保留库中原值，避免把 "sk-****abcd" 当成新密钥写进数据库。
        restoreMaskedApiKey(config);
        normalizeConfig(config);
        validateConfig(config);
        purgeDeletedDuplicate(config);
        ensureUniqueProviderAndConfigName(config);

        // 检查是否是该提供商的第一个配置
        long count = this.countByProvider(config.getProvider());

        if (config.getId() == null) {
            // 新增配置
            if (count == 0) {
                config.setIsDefault(1);
            } else if (config.getIsDefault() == null) {
                config.setIsDefault(0);
            }
            this.save(config);
        } else {
            // 更新配置
            if (config.getIsDefault() != null && config.getIsDefault() == 1) {
                // 如果要设置为默认，先清除其他默认配置
                LambdaUpdateWrapper<LLMGlobalConfig> clearWrapper = new LambdaUpdateWrapper<>();
                clearWrapper.eq(LLMGlobalConfig::getProvider, config.getProvider())
                           .ne(LLMGlobalConfig::getId, config.getId())
                           .set(LLMGlobalConfig::getIsDefault, 0);
                this.update(clearWrapper);
            }
            this.updateById(config);
        }

        return config;
    }

    private void purgeDeletedDuplicate(LLMGlobalConfig config) {
        LLMGlobalConfig existing = baseMapper.findAnyByProviderAndConfigName(
                config.getProvider(),
                config.getConfigName()
        );

        if (existing == null) {
            return;
        }

        boolean sameRecord = config.getId() != null && config.getId().equals(existing.getId());
        if (!sameRecord && existing.getDeleted() != null && existing.getDeleted() == 1) {
            baseMapper.hardDeleteById(existing.getId());
        }
    }

    /**
     * 掩码回写保护（迭代7 A3）：入参 apiKey 是打码形态时，说明前端未修改密钥，
     * 用库中原值覆盖，保证「展示脱敏」与「保存不丢密钥」同时成立。
     *
     * <p>仅更新场景（id 非空）可恢复；<b>新增场景</b>没有可恢复的原值，若放行会把
     * {@code sk-****abcd} 这类假密钥静默落库（且接口再打码后与真实密钥无异，难以察觉），
     * 因此显式拒绝。
     */
    private void restoreMaskedApiKey(LLMGlobalConfig config) {
        if (!ApiKeyMasker.isMasked(config.getApiKey())) {
            return;
        }
        if (config.getId() == null) {
            throw new IllegalArgumentException("新增配置时 API 密钥不能使用脱敏掩码，请填写真实密钥");
        }
        LLMGlobalConfig existing = this.getById(config.getId());
        if (existing != null) {
            config.setApiKey(existing.getApiKey());
        }
    }

    private void normalizeConfig(LLMGlobalConfig config) {
        config.setProvider(canonicalizeProvider(trimToNull(config.getProvider())));
        config.setConfigName(trimToNull(config.getConfigName()));
        config.setApiUrl(trimToNull(config.getApiUrl()));
        config.setApiKey(trimToNull(config.getApiKey()));
        config.setModel(trimToNull(config.getModel()));
        config.setTtsModel(trimToNull(config.getTtsModel()));
        config.setEmbeddingModel(trimToNull(config.getEmbeddingModel()));
        config.setImageModel(trimToNull(config.getImageModel()));
        config.setVideoModel(trimToNull(config.getVideoModel()));
        config.setMemoryEnabled(config.getMemoryEnabled() != null && config.getMemoryEnabled() == 1 ? 1 : 0);
    }

    private void validateConfig(LLMGlobalConfig config) {
        if (config.getProvider() == null) {
            throw new IllegalArgumentException("供应商不能为空");
        }
        if (config.getConfigName() == null) {
            throw new IllegalArgumentException("配置别名不能为空");
        }
        if (config.getApiUrl() == null) {
            throw new IllegalArgumentException("API 地址不能为空");
        }
        if (config.getApiKey() == null) {
            throw new IllegalArgumentException("API 密钥不能为空");
        }
        if (config.getModel() == null) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
    }

    private void ensureUniqueProviderAndConfigName(LLMGlobalConfig config) {
        LambdaQueryWrapper<LLMGlobalConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LLMGlobalConfig::getProvider, config.getProvider())
               .eq(LLMGlobalConfig::getConfigName, config.getConfigName());

        if (config.getId() != null) {
            wrapper.ne(LLMGlobalConfig::getId, config.getId());
        }

        if (this.count(wrapper) > 0) {
            throw new IllegalArgumentException("同一供应商下的配置别名不能重复");
        }
    }

    /**
     * 删除配置
     * 如果删除的是默认配置，自动将下一个配置设为默认
     */
    @Transactional
    public void deleteConfig(Long id) {
        LLMGlobalConfig config = this.getById(id);
        if (config == null) {
            return;
        }

        String provider = config.getProvider();
        boolean wasDefault = config.getIsDefault() == 1;

        baseMapper.hardDeleteById(id);

        // 如果删除的是默认配置，将下一个配置设为默认
        if (wasDefault) {
            List<LLMGlobalConfig> remaining = this.listByProvider(provider);
            if (!remaining.isEmpty()) {
                LLMGlobalConfig newDefault = remaining.get(0);
                newDefault.setIsDefault(1);
                this.updateById(newDefault);
            }
        }
    }

    /**
     * 统计某提供商的配置数量
     */
    private long countByProvider(String provider) {
        LambdaQueryWrapper<LLMGlobalConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LLMGlobalConfig::getProvider, provider);
        return this.count(wrapper);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String canonicalizeProvider(String provider) {
        if (provider == null) {
            return null;
        }

        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "open ai" -> "openai";
            case "deep seek" -> "deepseek";
            case "通义千问" -> "qwen";
            case "stepfun", "阶跃星辰" -> "step";
            case "ai ping" -> "ai_ping";
            case "智谱" -> "zhipu";
            case "agnes", "agnes ai" -> "agnes";
            case "api free", "apifree.ai", "skyclaw", "skyclaw-v1", "skyclaw-v1.0",
                 "skyclaw-v1-lite", "skyclaw-v1.0-lite", "skywork-ai/skyclaw-v1",
                 "skywork-ai/skyclaw-v1-lite" -> "apifree";
            case "volcengine", "ark", "agent_plan", "agent plan", "火山方舟" -> "volcengine_agent_plan";
            default -> provider.toLowerCase(Locale.ROOT);
        };
    }
}
