package com.paiagent.engine.agent.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 租户投影拦截器（迭代6）。
 *
 * <p>在工作流执行入口（{@code /api/executions/**}）把请求头 {@code X-Tenant-Id}
 * 解析为当前线程租户，写入 {@link TenantContext}，供工具网关的跨租户校验使用。
 *
 * <p><b>为什么用请求头而不是 JWT</b>：当前 PaiAgent 用户体系（{@code User}）没有
 * 租户字段，把租户并入登录态需要改用户表 + JWT 签发 + 全部调用方，破坏面大。
 * 请求头方案与 ERP 侧「平台管理员显式指定租户」同构：由受信任调用方声明租户，
 * 服务端只做「投影 + 校验」，不信任节点入参里的 tenantId（模型可控，不可信）。
 * 后续若用户体系增加租户字段，只需把 {@link #resolveTenantId} 改为读登录态，
 * 下游链路不变。
 *
 * <p>缺省值 {@code "global"}：与治理前行为一致，未声明租户的部署不因本拦截器改变语义。
 */
@Component
public class TenantProjectionInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TenantProjectionInterceptor.class);

    public static final String TENANT_HEADER = "X-Tenant-Id";

    /** 未声明租户时的兜底值（与 ToolGateway 的 global 豁免语义对齐）。 */
    public static final String DEFAULT_TENANT = "global";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String tenantId = resolveTenantId(request.getHeader(TENANT_HEADER));
        TenantContext.setCurrent(tenantId);
        log.debug("租户投影: {} -> {}", request.getRequestURI(), tenantId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 线程池复用下必须清除，防止租户串扰
        TenantContext.clear();
    }

    /** 解析租户：空/空白头兜底为 global；首尾空白裁剪。 */
    static String resolveTenantId(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return DEFAULT_TENANT;
        }
        return headerValue.trim();
    }
}
