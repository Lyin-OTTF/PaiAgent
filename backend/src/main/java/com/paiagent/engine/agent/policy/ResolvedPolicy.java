package com.paiagent.engine.agent.policy;

/**
 * 解析后的工具治理策略（非注解实例，便于在运行时无注解时提供默认值）。
 */
public class ResolvedPolicy {

    private final AutonomyLevel level;
    private final boolean write;
    private final String scope;
    private final boolean idempotent;
    private final boolean requiresApproval;

    public ResolvedPolicy(AutonomyLevel level, boolean write, String scope,
                          boolean idempotent, boolean requiresApproval) {
        this.level = level;
        this.write = write;
        this.scope = scope;
        this.idempotent = idempotent;
        this.requiresApproval = requiresApproval;
    }

    public static ResolvedPolicy fromAnnotation(ToolPolicy annotation) {
        if (annotation == null) {
            return defaults();
        }
        return new ResolvedPolicy(
                annotation.level(),
                annotation.write(),
                annotation.scope(),
                annotation.idempotent(),
                annotation.requiresApproval());
    }

    public static ResolvedPolicy defaults() {
        return new ResolvedPolicy(AutonomyLevel.OBSERVE, false, "global", false, false);
    }

    public AutonomyLevel level() {
        return level;
    }

    public boolean isWrite() {
        return write;
    }

    public String scope() {
        return scope;
    }

    public boolean isIdempotent() {
        return idempotent;
    }

    public boolean isRequiresApproval() {
        return requiresApproval;
    }
}
