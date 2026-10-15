package com.paiagent.engine.memory;

import java.time.Duration;

/**
 * 记忆分层范围（迭代2：持久化分层记忆）。
 *
 * <ul>
 *   <li>WORKING  —— 当前执行/会话内的易失工作记忆，默认 30 分钟过期。</li>
 *   <li>SESSION —— 绑定某个会话（sessionId），默认 24 小时过期。</li>
 *   <li>USER    —— 绑定某个用户（userId）的长期记忆，不过期。</li>
 *   <li>TEAM    —— 跨用户共享的团队/租户（tenantId）记忆，不过期。</li>
 * </ul>
 *
 * 兼容历史 scope 取值：workflow -> WORKING，global -> TEAM。
 */
public enum MemoryScope {

    WORKING("WORKING", Duration.ofMinutes(30)),
    SESSION("SESSION", Duration.ofHours(24)),
    USER("USER", null),
    TEAM("TEAM", null);

    private final String code;
    private final Duration defaultTtl;

    MemoryScope(String code, Duration defaultTtl) {
        this.code = code;
        this.defaultTtl = defaultTtl;
    }

    /** 持久化到数据库 scope 列的取值 */
    public String code() {
        return code;
    }

    /** 该层级的默认存活时长；null 表示永不过期 */
    public Duration defaultTtl() {
        return defaultTtl;
    }

    /** 是否属于需要 Redis 会话级缓存的易失层 */
    public boolean isEphemeral() {
        return this == WORKING || this == SESSION;
    }

    /**
     * 将历史或外部传入的 scope 字符串映射为 {@link MemoryScope}。
     * 无法识别时默认 {@link #WORKING}。
     */
    public static MemoryScope from(String scope) {
        if (scope == null || scope.isBlank()) {
            return WORKING;
        }
        String normalized = scope.trim().toUpperCase();
        for (MemoryScope value : values()) {
            if (value.code.equals(normalized)) {
                return value;
            }
        }
        return switch (normalized) {
            case "WORKFLOW" -> WORKING;
            case "GLOBAL" -> TEAM;
            case "USER" -> USER;
            case "TEAM" -> TEAM;
            case "SESSION" -> SESSION;
            default -> WORKING;
        };
    }
}
