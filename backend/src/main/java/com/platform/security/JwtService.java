package com.platform.security;

import io.jsonwebtoken.Claims;
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
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final long expirySeconds;

    public JwtService(@Value("${security.jwt.secret}") String secret,
                      @Value("${security.jwt.expiry-seconds:7200}") long expirySeconds) {
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
