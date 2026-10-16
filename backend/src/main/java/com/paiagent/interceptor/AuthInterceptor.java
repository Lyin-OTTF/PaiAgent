package com.paiagent.interceptor;

import com.paiagent.common.UserContext;
import com.paiagent.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器。
 *
 * <p>迭代7（评审报告 A1/A2）：认证通过后除注入 {@code request} attribute 外，
 * 同时把登录用户名写入 {@link UserContext}，供 Service 层做数据归属过滤；
 * 请求结束时在 {@code afterCompletion} 清理 ThreadLocal，避免线程池复用串扰。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    
    @Autowired
    private AuthService authService;
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equals(request.getMethod())) {
            // 预检请求不承载业务身份：清理可能残留的上下文，直接放行
            UserContext.clear();
            return true;
        }
        
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        
        if (token == null) {
            token = request.getParameter("token");
        }
        
        if (token == null || !authService.validateToken(token)) {
            UserContext.clear();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"未认证或认证已过期\"}");
            return false;
        }
        
        String username = authService.getUsernameByToken(token);
        request.setAttribute("username", username);
        // 迭代7：归属维度投影（业务层据此隔离数据）
        UserContext.setCurrent(username);
        
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 迭代7：请求收尾清理，防止线程池复用导致身份串扰
        UserContext.clear();
    }
}