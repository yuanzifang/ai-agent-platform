package com.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Bearer JWT 解析过滤器：解析成功写入 SecurityContext；同时生成 traceId 供统一响应与日志串联。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        MDC.put("traceId", UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                try {
                    JwtPrincipal principal = jwtService.parse(header.substring(7));
                    var auth = new UsernamePasswordAuthenticationToken(principal, null,
                            principal.roles().stream().map(r -> (org.springframework.security.core.GrantedAuthority) () -> "ROLE_" + r).toList());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } catch (io.jsonwebtoken.JwtException ignored) {
                    // 无效令牌：不设置认证信息，由 Security 的 401 入口统一返回
                }
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
            SecurityContextHolder.clearContext();
        }
    }
}
