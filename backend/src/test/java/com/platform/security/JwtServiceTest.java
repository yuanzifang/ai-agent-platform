package com.platform.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JWT 安全边界：令牌类型隔离、签名校验、刷新令牌不可预测。 */
class JwtServiceTest {

    private static final String SECRET = "unit-test-jwt-secret-at-least-32-bytes-long";
    private final JwtService service = new JwtService(SECRET, 3600);

    @Test
    void accessTokenRoundTripsPrincipalClaims() {
        JwtPrincipal principal = new JwtPrincipal(42L, 9L, true,
                List.of("ADMIN"), List.of("agent:read", "kb:write"));

        JwtPrincipal parsed = service.parse(service.createAccessToken(principal));

        assertEquals(42L, parsed.userId());
        assertEquals(9L, parsed.tenantId());
        assertTrue(parsed.platformAdmin());
        assertEquals(List.of("ADMIN"), parsed.roles());
        assertEquals(List.of("agent:read", "kb:write"), parsed.permissions());
        assertTrue(parsed.hasPermission("anything"));
    }

    @Test
    void mfaTokenCannotBeUsedAsBearerAccessToken() {
        String mfa = service.createMfaToken(42L);

        assertEquals(42L, service.parseMfaToken(mfa));
        assertThrows(JwtException.class, () -> service.parse(mfa),
                "MFA 短期凭证不能被 JwtAuthFilter 当作登录态");
    }

    @Test
    void accessTokenCannotBeUsedAsMfaToken() {
        JwtPrincipal principal = new JwtPrincipal(42L, 9L, false, List.of(), List.of());
        assertNull(service.parseMfaToken(service.createAccessToken(principal)));
        assertNull(service.parseMfaToken("not-a-token"));
    }

    @Test
    void tamperedOrWrongKeyTokenIsRejected() {
        JwtPrincipal principal = new JwtPrincipal(42L, 9L, false, List.of(), List.of());
        String token = service.createAccessToken(principal);
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThrows(JwtException.class, () -> service.parse(tampered));

        JwtService other = new JwtService(SECRET + "-different-key", 3600);
        assertThrows(JwtException.class, () -> other.parse(token));
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService expired = new JwtService(SECRET, -1);
        JwtPrincipal principal = new JwtPrincipal(42L, 9L, false, List.of(), List.of());
        String token = expired.createAccessToken(principal);
        assertThrows(JwtException.class, () -> expired.parse(token));
    }

    @Test
    void refreshTokenIsRandom64HexAndOnlyHashIsStable() {
        String first = service.createRefreshToken();
        String second = service.createRefreshToken();

        assertTrue(first.matches("[0-9a-f]{64}"));
        assertNotEquals(first, second);
        assertNotEquals(first, service.hash(first));
        assertTrue(service.hash(first).matches("[0-9a-f]{64}"));
        assertEquals(service.hash(first), service.hash(first));
        assertNotEquals(service.hash(first), service.hash(second));
    }

    @Test
    void expirySecondsIsExposedForCookieMaxAge() {
        assertEquals(3600L, service.getExpirySeconds());
    }
}
