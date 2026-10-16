package com.paiagent.config;

import com.paiagent.engine.agent.tenant.TenantProjectionInterceptor;
import com.paiagent.interceptor.AuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * Web MVC 配置类
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    
    @Autowired
    private AuthInterceptor authInterceptor;

    @Autowired
    private TenantProjectionInterceptor tenantProjectionInterceptor;

    @Value("${paiagent.cors.allowed-origin-patterns:http://localhost:*,http://127.0.0.1:*}")
    private String allowedOriginPatterns;
    
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("http://localhost:5173",
                        "http://127.0.0.1:5173",
                        "http://localhost:5174",
                        "http://127.0.0.1:5174")
                .allowedOriginPatterns(Arrays.stream(allowedOriginPatterns.split(","))
                        .map(String::trim)
                        .filter(pattern -> !pattern.isEmpty())
                        .toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
    
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/auth/refresh", "/api/auth/current", "/api/node-types", "/swagger-ui/**", "/v3/api-docs/**");

        // 租户投影（迭代6）：从 X-Tenant-Id 请求头解析租户写入 TenantContext，
        // 使 ToolGateway 跨租户校验与 ActionLedger 租户记录真实生效。
        // 顺序在 authInterceptor 之后：仅对已认证请求投影。
        // 迭代7 修正（评审报告中危项）：覆盖范围从 /api/workflows/** 扩大到全部业务接口——
        // 修复前知识库/LLM 配置/MCP/治理等接口都拿不到租户上下文，隔离链路半途而废；
        // 投影本身只写 ThreadLocal，对读接口无副作用。
        registry.addInterceptor(tenantProjectionInterceptor)
                .addPathPatterns("/api/**");
    }
}
