package com.paiagent.common;

/**
 * 当前登录用户上下文（迭代7 · 数据归属，对应评审报告 A1/A2）。
 *
 * <p>由 {@code AuthInterceptor} 在请求线程从 JWT 解出用户名后写入，业务层（Service）
 * 读取它做数据归属过滤，使「工作流 / 知识库」等资源具备真实的归属维度——
 * 修复前实体无 owner 字段、查询无任何过滤，任意登录用户可读写全部资源。
 *
 * <p><b>为什么用 ThreadLocal</b>：与 {@code TenantContext} 同构——归属是「本次请求」
 * 的属性，不应穿透方法签名；由拦截器在 {@code afterCompletion} 清理，避免线程池复用串扰。
 *
 * <p><b>兜底语义</b>：无 HTTP 上下文（定时任务、HITL 补执行线程、单元测试）时归
 * {@link #SYSTEM_OWNER}。写操作因此「必有归属」，不会产生 owner=NULL 的孤儿数据；
 * 读取侧再把 {@link #SYSTEM_OWNER} 资源视为系统共享（见 Service 的归属过滤规则）。
 */
public final class UserContext {

    /** 无请求上下文时的系统归属（内部任务创建的资源归它所有，对所有用户可见）。 */
    public static final String SYSTEM_OWNER = "system";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private UserContext() {
    }

    /** 写入当前登录用户；null/空白等同于清除。 */
    public static void setCurrent(String username) {
        if (username == null || username.isBlank()) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(username.trim());
    }

    /** 当前登录用户；无上下文时返回 null（不兜底，调用方自行决定语义）。 */
    public static String getCurrent() {
        return CURRENT.get();
    }

    /**
     * 当前写入归属：登录用户 &gt; {@link #SYSTEM_OWNER}。
     * 新增资源统一用它，保证 owner 永不为空。
     */
    public static String currentOwner() {
        String current = CURRENT.get();
        return current != null && !current.isBlank() ? current : SYSTEM_OWNER;
    }

    /** 清理上下文（拦截器 afterCompletion 调用，防线程复用串扰）。 */
    public static void clear() {
        CURRENT.remove();
    }
}
