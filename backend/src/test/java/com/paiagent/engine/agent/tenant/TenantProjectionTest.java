package com.paiagent.engine.agent.tenant;

import com.paiagent.engine.executor.impl.ReActAgentNodeExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 迭代6：租户投影测试。
 *
 * <p>覆盖三层：
 * <ul>
 *   <li>{@link TenantContext}：ThreadLocal 语义（set/get/clear/兜底）；</li>
 *   <li>{@link TenantProjectionInterceptor}：请求头解析（带头/空头/无头/空白裁剪）
 *       与 afterCompletion 清理（防线程池复用串扰）；</li>
 *   <li>{@code ReActAgentNodeExecutor.resolveTenantId}：优先级
 *       input 显式 &gt; TenantContext &gt; "global"。</li>
 * </ul>
 */
class TenantProjectionTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // TenantContext
    // ------------------------------------------------------------------

    @Test
    @DisplayName("TenantContext：set/get/clear/兜底语义")
    void tenantContextBasics() {
        assertNull(TenantContext.getCurrent(), "初始应为 null");
        assertEquals("global", TenantContext.getCurrentOrDefault("global"));

        TenantContext.setCurrent("t-100");
        assertEquals("t-100", TenantContext.getCurrent());
        assertEquals("t-100", TenantContext.getCurrentOrDefault("global"));

        TenantContext.clear();
        assertNull(TenantContext.getCurrent());

        TenantContext.setCurrent(null);
        assertNull(TenantContext.getCurrent(), "setCurrent(null) 等价于清除");

        TenantContext.setCurrent("  ");
        assertEquals("global", TenantContext.getCurrentOrDefault("global"),
                "空白租户按未设置处理");
    }

    // ------------------------------------------------------------------
    // TenantProjectionInterceptor
    // ------------------------------------------------------------------

    @Test
    @DisplayName("拦截器：X-Tenant-Id 请求头投影到 TenantContext，结束后清理")
    void interceptorProjectsHeaderAndClears() {
        TenantProjectionInterceptor interceptor = new TenantProjectionInterceptor();

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/executions/1/execute");
        request.addHeader(TenantProjectionInterceptor.TENANT_HEADER, "tenant-A");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals("tenant-A", TenantContext.getCurrent(), "preHandle 后租户应已投影");

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
        assertNull(TenantContext.getCurrent(), "afterCompletion 必须清理，防线程池复用串扰");
    }

    @Test
    @DisplayName("拦截器：无请求头/空头/空白头均兜底为 global")
    void interceptorFallsBackToGlobal() {
        TenantProjectionInterceptor interceptor = new TenantProjectionInterceptor();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/executions/1/execute");

        // 无头
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals("global", TenantContext.getCurrent());
        TenantContext.clear();

        // 空串
        request.addHeader(TenantProjectionInterceptor.TENANT_HEADER, "");
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals("global", TenantContext.getCurrent());
        TenantContext.clear();

        // 空白串（裁剪后为空）
        request = new MockHttpServletRequest("POST", "/api/executions/1/execute");
        request.addHeader(TenantProjectionInterceptor.TENANT_HEADER, "   ");
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals("global", TenantContext.getCurrent());
    }

    @Test
    @DisplayName("拦截器：请求头首尾空白被裁剪")
    void interceptorTrimsHeader() {
        TenantProjectionInterceptor interceptor = new TenantProjectionInterceptor();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/executions/1/execute");
        request.addHeader(TenantProjectionInterceptor.TENANT_HEADER, "  tenant-B  ");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals("tenant-B", TenantContext.getCurrent());
    }

    // ------------------------------------------------------------------
    // ReActAgentNodeExecutor.resolveTenantId（包内静态方法）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("租户解析优先级：input 显式 > TenantContext > global")
    void resolveTenantIdPriority() {
        // 1. 仅 TenantContext
        TenantContext.setCurrent("ctx-tenant");
        assertEquals("ctx-tenant", ReActAgentNodeExecutor.resolveTenantId(Map.of()));

        // 2. input 显式覆盖 TenantContext
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("tenantId", "input-tenant");
        assertEquals("input-tenant", ReActAgentNodeExecutor.resolveTenantId(input));

        // 3. 都缺失 -> global
        TenantContext.clear();
        assertEquals("global", ReActAgentNodeExecutor.resolveTenantId(Map.of()));

        // 4. input 空白值不覆盖 TenantContext（视为未声明）
        TenantContext.setCurrent("ctx-tenant");
        Map<String, Object> blank = new LinkedHashMap<>();
        blank.put("tenantId", "   ");
        assertEquals("ctx-tenant", ReActAgentNodeExecutor.resolveTenantId(blank));

        // 5. null input 安全
        assertEquals("ctx-tenant", ReActAgentNodeExecutor.resolveTenantId(null));
    }
}
