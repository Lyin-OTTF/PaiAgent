package com.paiagent.engine.agent.tenant;

/**
 * 租户上下文（迭代6：租户投影的线程级载体）。
 *
 * <p>解决「{@code ToolGateway} 跨租户校验形同虚设」问题：ReAct 执行上下文的
 * tenantId 此前恒为 {@code "global"}（工作流 input 从不注入租户），
 * 导致 {@code policy.scope()} 非 global/shared 的写工具永远校验通过。
 *
 * <p>链路：{@link TenantProjectionInterceptor} 在执行入口从 {@code X-Tenant-Id}
 * 请求头解析租户放入本上下文 → {@code ReActAgentNodeExecutor} 构建
 * {@code ToolExecutionContext} 时读取 → {@code ToolGateway} 第 1 步跨租户校验
 * 真正生效 → {@code ActionLedger} 记录真实租户。
 *
 * <p>注意：请求线程结束后必须 {@link #clear()}，防止线程池复用导致租户串扰。
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /** 设置当前线程的租户（null 视为清除）。 */
    public static void setCurrent(String tenantId) {
        if (tenantId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(tenantId);
        }
    }

    /** 获取当前线程租户；未设置时返回 null（由调用方决定兜底值）。 */
    public static String getCurrent() {
        return CURRENT.get();
    }

    /** 获取当前线程租户；未设置时返回 {@code defaultValue}。 */
    public static String getCurrentOrDefault(String defaultValue) {
        String current = CURRENT.get();
        return current == null || current.isBlank() ? defaultValue : current;
    }

    /** 清除当前线程租户（必须在请求/执行结束后调用）。 */
    public static void clear() {
        CURRENT.remove();
    }
}
