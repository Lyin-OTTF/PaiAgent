package com.paiagent.engine.agent.policy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明某个 Agent 工具（{@code AgentTool} 实现类）的权限与治理属性。
 * 由 {@link PolicyRegistry} 在启动时扫描并注册。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolPolicy {
    /** 工具所需的最低自治等级。运行时允许等级低于此值则需审批或被拒绝。 */
    AutonomyLevel level() default AutonomyLevel.OBSERVE;

    /** 该工具是否会产生写操作（改变业务状态）。 */
    boolean write() default false;

    /** 工具作用范围/租户作用域；"shared" 表示跨租户共享，否则为具体租户 ID。 */
    String scope() default "global";

    /** 该工具是否幂等（相同入参重复调用不产生额外副作用）。 */
    boolean idempotent() default false;

    /** 是否强制要求人工审批（即使运行时允许等级足够）。 */
    boolean requiresApproval() default false;
}
