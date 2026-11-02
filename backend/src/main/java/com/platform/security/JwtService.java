package com.platform.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

/**
 * JWT 签发与解析（架构 9.1）：访问令牌短期有效；刷新令牌为随机串，哈希后存 user_session。
 * 生产 profile 禁止使用内置默认密钥（该密钥在公开仓库里可见，等于认证旁路）。
 */
@Component
public class JwtService {

    static final String BUILTIN_DEFAULT_SECRET = "dev-only-secret-change-me-0123456789abcdef";

    private final SecretKey key;
    private final long expirySeconds;

    /** 测试与既有调用便利构造：无 Environment（不做 prod 守卫）。 */
    public JwtService(String secret, long expirySeconds) {
        this(secret, expirySeconds, null);
    }

    public JwtService(@Value("${security.jwt.secret}") String secret,
                      @Value("${security.jwt.expiry-seconds:7200}") long expirySeconds,
                      org.springframework.core.env.Environment environment) {
        String trimmed = secret == null ? "" : secret.trim();
        if (environment != null && java.util.Arrays.asList(environment.getActiveProfiles()).contains("prod")
                && (trimmed.isEmpty() || BUILTIN_DEFAULT_SECRET.equals(trimmed))) {
            throw new IllegalStateException(
                    "生产环境必须配置独立的 security.jwt.secret（≥32 字符），禁止为空或使用内置默认值");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirySeconds = expirySeconds;
    }

    public String createAccessToken(JwtPrincipal principal) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(principal.userId()))
                .claim("tenantId", principal.tenantId())
                .claim("platformAdmin", principal.platformAdmin())
                .claim("roles", principal.roles()).claim("permissions", principal.permissions())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirySeconds)))
                .signWith(key)
                .compact();
    }

    @SuppressWarnings("unchecked")
    public JwtPrincipal parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        // 只接受访问令牌：MFA 短期凭证同样由本密钥签发，不能被 Bearer 过滤器当成登录态。
        if (claims.get("purpose", String.class) != null) {
            throw new JwtException("not an access token");
        }
        Long tenantId = claims.get("tenantId", Long.class);
        Boolean platformAdmin = claims.get("platformAdmin", Boolean.class);
        List<String> roles = claims.get("roles", List.class);
        List<String> permissions = claims.get("permissions", List.class);
        return new JwtPrincipal(
                Long.valueOf(claims.getSubject()),
                tenantId,
                Boolean.TRUE.equals(platformAdmin),
                roles == null ? List.of() : roles,
                permissions == null ? List.of() : permissions);
    }

    /** 登录第一步通过后签发的短期 MFA 凭证（5 分钟，purpose=mfa）。 */
    public String createMfaToken(Long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("purpose", "mfa")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(300)))
                .signWith(key)
                .compact();
    }

    /** 校验 MFA 凭证，返回 userId；无效/过期返回 null。 */
    public Long parseMfaToken(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            if (!"mfa".equals(claims.get("purpose", String.class))) return null;
            return Long.valueOf(claims.getSubject());
        } catch (Exception e) {
            return null;
        }
    }

    public long getExpirySeconds() {
        return expirySeconds;
    }

    /** 随机刷新令牌（64 hex 字符，不可枚举）。 */
    public String createRefreshToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** SHA-256 哈希，user_session 仅存哈希。 */
    public String hash(String rawToken) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
