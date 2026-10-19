package com.paiagent.controller;

import com.paiagent.common.ApiKeyMasker;
import com.paiagent.common.Result;
import com.paiagent.entity.LLMGlobalConfig;
import com.paiagent.service.LLMGlobalConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 全局模型配置管理控制器。
 *
 * <p>迭代7（评审报告 A3）：所有出站响应统一对 {@code apiKey} 打码下发——
 * 修复前四个查询接口直接返回实体，任何登录用户都能读到全部供应商的明文密钥。
 */
@Slf4j
@Tag(name = "全局模型配置接口")
@RestController
@RequestMapping("/api/llm-config")
public class LLMConfigController {

    @Autowired
    private LLMGlobalConfigService llmGlobalConfigService;

    @Operation(summary = "获取指定提供商的配置列表")
    @GetMapping("/{provider}")
    public Result<List<LLMGlobalConfig>> listByProvider(@PathVariable String provider) {
        List<LLMGlobalConfig> configs = llmGlobalConfigService.listByProvider(provider);
        return Result.success(masked(configs));
    }

    @Operation(summary = "获取所有配置列表")
    @GetMapping
    public Result<List<LLMGlobalConfig>> listAll() {
        List<LLMGlobalConfig> configs = llmGlobalConfigService.list();
        return Result.success(masked(configs));
    }

    @Operation(summary = "获取配置详情")
    @GetMapping("/detail/{id}")
    public Result<LLMGlobalConfig> getById(@PathVariable Long id) {
        LLMGlobalConfig config = llmGlobalConfigService.getById(id);
        if (config == null) {
            return Result.error("配置不存在");
        }
        return Result.success(masked(config));
    }

    @Operation(summary = "获取指定提供商的默认配置")
    @GetMapping("/default/{provider}")
    public Result<LLMGlobalConfig> getDefaultConfig(@PathVariable String provider) {
        LLMGlobalConfig config = llmGlobalConfigService.getDefaultConfig(provider);
        return Result.success(masked(config));
    }

    @Operation(summary = "保存配置（新增或更新）")
    @PostMapping
    public Result<LLMGlobalConfig> saveConfig(@RequestBody LLMGlobalConfig config) {
        try {
            LLMGlobalConfig saved = llmGlobalConfigService.saveConfig(config);
            return Result.success(masked(saved));
        } catch (IllegalArgumentException e) {
            return Result.error("保存配置失败: " + e.getMessage());
        } catch (DuplicateKeyException e) {
            return Result.error("保存配置失败: 同一供应商下的配置别名不能重复");
        } catch (Exception e) {
            return Result.error("保存配置失败，请稍后重试");
        }
    }

    @Operation(summary = "删除配置")
    @DeleteMapping("/{id}")
    public Result<Void> deleteConfig(@PathVariable Long id) {
        llmGlobalConfigService.deleteConfig(id);
        return Result.success();
    }

    @Operation(summary = "设置默认配置")
    @PostMapping("/{id}/default")
    public Result<Void> setDefaultConfig(@PathVariable Long id) {
        try {
            llmGlobalConfigService.setDefaultConfig(id);
            return Result.success();
        } catch (IllegalArgumentException e) {
            // 业务校验消息（如「配置不存在」）为可读文案，保留回显
            return Result.error("设置默认配置失败: " + e.getMessage());
        } catch (Exception e) {
            // 迭代10：未预期异常细节只入日志，回显通用文案
            log.error("设置默认配置失败, id={}", id, e);
            return Result.error("设置默认配置失败，请稍后重试");
        }
    }

    /**
     * 出站脱敏（迭代7 A3）：统一对 apiKey 打码后再返回。
     * 与 {@code LLMGlobalConfigService.saveConfig} 的「掩码回写保护」配对，
     * 实现「前端可辨识展示、密钥不落前端、保存时不会被掩码写坏」。
     */
    private List<LLMGlobalConfig> masked(List<LLMGlobalConfig> configs) {
        if (configs != null) {
            configs.forEach(this::maskInPlace);
        }
        return configs;
    }

    private LLMGlobalConfig masked(LLMGlobalConfig config) {
        if (config != null) {
            maskInPlace(config);
        }
        return config;
    }

    private void maskInPlace(LLMGlobalConfig config) {
        config.setApiKey(ApiKeyMasker.mask(config.getApiKey()));
    }
}
