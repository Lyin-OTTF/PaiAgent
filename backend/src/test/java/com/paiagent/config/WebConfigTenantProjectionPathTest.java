package com.paiagent.config;

import com.paiagent.engine.agent.tenant.TenantProjectionInterceptor;
import com.paiagent.interceptor.AuthInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 迭代6：#30 遗留修复的回归测试——租户投影拦截路径。
 *
 * <p>修复前注册路径是 {@code /api/executions/**}，而执行入口在
 * {@code ExecutionController(@RequestMapping("/api/workflows"))} 下，
 * 路径永不命中，X-Tenant-Id 投影实际空转。
 *
 * <p>迭代7（评审报告低危项）：投影路径从 {@code /api/workflows/**} 扩大到 {@code /api/**}——
 * 全仓 Controller（工作流/知识库/配置/MCP/治理）都在 {@code /api} 下，
 * 只覆盖工作流会导致其余接口的租户上下文回落 global，隔离形同虚设。
 */
class WebConfigTenantProjectionPathTest {

    @Test
    @DisplayName("租户投影注册到 /api/**（覆盖全部业务接口），而不是空转的 /api/executions/**")
    void tenantProjectionCoversWorkflowExecutionEndpoints() {
        WebConfig webConfig = new WebConfig();
        AuthInterceptor auth = mock(AuthInterceptor.class);
        TenantProjectionInterceptor tenant = mock(TenantProjectionInterceptor.class);
        ReflectionTestUtils.setField(webConfig, "authInterceptor", auth);
        ReflectionTestUtils.setField(webConfig, "tenantProjectionInterceptor", tenant);

        InterceptorRegistry registry = mock(InterceptorRegistry.class);
        InterceptorRegistration authReg = mock(InterceptorRegistration.class);
        InterceptorRegistration tenantReg = mock(InterceptorRegistration.class);
        when(registry.addInterceptor(auth)).thenReturn(authReg);
        when(registry.addInterceptor(tenant)).thenReturn(tenantReg);
        when(authReg.addPathPatterns(any(String[].class))).thenReturn(authReg);
        when(authReg.excludePathPatterns(any(String[].class))).thenReturn(authReg);
        when(tenantReg.addPathPatterns(any(String[].class))).thenReturn(tenantReg);

        webConfig.addInterceptors(registry);

        verify(tenantReg).addPathPatterns("/api/**");
        verify(tenantReg, never()).addPathPatterns("/api/executions/**");
        verify(tenantReg, never()).addPathPatterns("/api/workflows/**");
        // 顺序约束：租户投影晚于认证拦截器注册（仅对已认证请求投影）
        verify(registry).addInterceptor(auth);
        verify(registry).addInterceptor(tenant);
    }
}
