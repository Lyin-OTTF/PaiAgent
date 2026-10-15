package com.paiagent.engine.agent.policy;

/**
 * 工具自治等级（受限自治层核心枚举）。
 * 等级从低到高代表「干预程度」递增：等级越高，对系统状态的影响越大，越需要管控。
 */
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
            return OBSERVE;
        }
    }
}
