package com.platform.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * API 安全响应头。页面 CSP 由 Nginx 和 index.html 承担；这里保证直连后端/旁路代理时也有基础防护。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String API_CSP =
            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!response.containsHeader("Content-Security-Policy")) {
            response.setHeader("Content-Security-Policy", API_CSP);
        }
        if (!response.containsHeader("X-Content-Type-Options")) {
            response.setHeader("X-Content-Type-Options", "nosniff");
        }
        if (!response.containsHeader("X-Frame-Options")) {
            response.setHeader("X-Frame-Options", "DENY");
        }
        if (!response.containsHeader("Referrer-Policy")) {
            response.setHeader("Referrer-Policy", "no-referrer");
        }
        if (!response.containsHeader("Permissions-Policy")) {
            response.setHeader("Permissions-Policy", "camera=(self), microphone=(self), geolocation=()");
        }
        filterChain.doFilter(request, response);
    }
}
