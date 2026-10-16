package com.paiagent.engine.agent.policy;

import lombok.extern.slf4j.Slf4j;

/**
 * 工具自治等级（受限自治层核心枚举）。
 * 等级从低到高代表「干预程度」递增：等级越高，对系统状态的影响越大，越需要管控。
 */
@Slf4j
public enum AutonomyLevel {
    OBSERVE(0, "只读，不改变状态"),
    SUGGEST(1, "只输出建议"),
    DRAFT(2, "生成待提交操作"),
    EXECUTE(3, "低风险自动执行"),
    ESCALATE(4, "必须人工介入");

    private final int rank;
    private final String description;

    AutonomyLevel(int rank, String description) {
        this.rank = rank;
        this.description = description;
    }

    public int rank() {
        return rank;
    }

    public String description() {
        return description;
    }

    /** 当前等级是否高于 another（风险更高、需要更多管控）。 */
    public boolean isHigherThan(AutonomyLevel another) {
        return this.rank > another.rank;
    }

    public static AutonomyLevel fromName(String name) {
        if (name == null || name.isBlank()) {
            return OBSERVE;
        }
        try {
            return AutonomyLevel.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // 迭代7：非法值仍然保守回落 OBSERVE，但不再静默——
            // 否则「配置写错」会表现为「工具莫名被拒/被降级」，排查成本高。
            log.warn("无法识别的自治等级 [{}]，保守回落为 OBSERVE", name);
            return OBSERVE;
        }
    }
}
