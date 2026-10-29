package com.platform.common.config;

import jakarta.servlet.DispatcherType;
import com.platform.security.JwtAuthFilter;
import com.platform.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // SSE 完成时的 ASYNC dispatch 不携带安全上下文，需放行（否则 done 事件丢失）
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        // 匿名接口（需求文档 7.2）
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/api/v1/auth/logout",
                                "/api/v1/auth/invitations/accept", "/api/v1/auth/mfa/verify",
                                "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password",
                                "/api/v1/share/**", "/api/v1/open/**", "/api/v1/channels/**", "/api/v1/auth/sso/**",
                                // 统一 API 网关公开入口（X-Gateway-Key 在控制器内校验）
                                "/api/v1/gateway/call/**", "/api/v1/gateway/ws/**",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                                // OAuth 2.1 浏览器回调（一次性 state 校验）
                                "/api/v1/mcp-servers/oauth/callback",
                                // 平台自身作为 MCP Server（API Key 在控制器内校验）
                                "/mcp",
                                // 仅容器内网供 Prometheus 抓取 / Alertmanager 推送；Nginx 不反代这些路径
                                "/actuator/health/**", "/actuator/prometheus", "/internal/**",
                                // 国际化读取公开（登录页即需语言包）；管理端点另行鉴权
                                "/api/v1/i18n/locales", "/api/v1/i18n/messages/*").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setCharacterEncoding("UTF-8");
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    res.getWriter().write("{\"code\":401,\"message\":\"未认证或登录已过期\",\"data\":null}");
                }))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
