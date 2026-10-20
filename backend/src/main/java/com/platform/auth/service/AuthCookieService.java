package com.platform.auth.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Refresh Token 仅通过 HttpOnly + SameSite=Strict Cookie 下发，前端 JS 不可读取。
 * Access Token 仍走 Authorization 头（短期，默认 30 分钟）。
 */
@Component
public class AuthCookieService {

    public static final String COOKIE_NAME = "refresh_token";

    @Value("${security.auth.cookie-secure:false}")
    private boolean secure;

    @Value("${security.auth.refresh-cookie-max-age-seconds:604800}")
    private long maxAgeSeconds;

    public ResponseCookie create(String refreshToken) {
        return ResponseCookie.from(COOKIE_NAME, refreshToken)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .build();
    }

    public ResponseCookie clear() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(0)
                .build();
    }

    /** Cookie 优先；body 兼容旧客户端。 */
    public String resolve(String bodyToken, HttpServletRequest request) {
        if (bodyToken != null && !bodyToken.isBlank()) return bodyToken;
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName()) && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
