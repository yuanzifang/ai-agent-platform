package com.platform.common.config;

import com.platform.common.exception.BusinessException;
import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 租户上下文注入（架构 2.2）：以 JWT 中 tenantId 为准；X-Tenant-Id 不一致返回 403。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TenantInterceptor())
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/invitations/accept");
    }

    static class TenantInterceptor implements HandlerInterceptor {

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                 Object handler) throws Exception {
            if (SecurityContextHolder.getContext().getAuthentication() != null
                    && SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                        instanceof JwtPrincipal principal) {
                String headerTenant = request.getHeader("X-Tenant-Id");
                Long tenantId = principal.tenantId();
                // 平台管理员跨租户：未携带自身租户时，以 X-Tenant-Id 作为当前操作租户（需求文档 4.3 租户切换）
                if (principal.platformAdmin() && tenantId == null
                        && headerTenant != null && !headerTenant.isBlank()) {
                    try {
                        tenantId = Long.valueOf(headerTenant);
                    } catch (NumberFormatException e) {
                        tenantId = null;
                    }
                }
                if (headerTenant != null && !headerTenant.isBlank() && tenantId != null
                        && !String.valueOf(tenantId).equals(headerTenant)) {
                    response.setStatus(403);
                    response.setCharacterEncoding("UTF-8");
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"code\":403,\"message\":\"租户标识与登录信息不一致\",\"data\":null}");
                    return false;
                }
                com.platform.common.context.TenantContext.setTenantId(tenantId);
            }
            return true;
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                    Object handler, Exception ex) {
            com.platform.common.context.TenantContext.clear();
        }
    }
}
