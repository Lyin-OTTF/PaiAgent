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

        // 租户投影（迭代6）：执行入口解析 X-Tenant-Id 写入 TenantContext，
        // 使 ToolGateway 跨租户校验与 ActionLedger 租户记录真实生效。
        // 顺序在 authInterceptor 之后：仅对已认证请求投影。
        // 路径修正：执行入口在 ExecutionController（@RequestMapping("/api/workflows")）下，
        // 旧路径 /api/executions/** 无任何 Controller 命中，投影实际不生效；
        // /api/workflows/** 覆盖 execute / execute/stream / resume 全部执行族端点，
        // 读端点投影仅写 ThreadLocal，无副作用。
        registry.addInterceptor(tenantProjectionInterceptor)
                .addPathPatterns("/api/workflows/**");
    }
}
