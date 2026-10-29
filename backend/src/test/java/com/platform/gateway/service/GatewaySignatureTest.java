package com.platform.gateway.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GatewaySignatureTest {

    @Test
    void verifiesCanonicalSignature() {
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = "nonce-123456";
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        String canonical = GatewaySignature.canonical("POST", "/api/v1/gateway/call/x", "a=1", ts, nonce, body);
        String signature = GatewaySignature.hmacHex("secret", canonical);
        assertNull(GatewaySignature.verifyHeader("secret", "POST", "/api/v1/gateway/call/x", "a=1",
                ts, nonce, body, signature));
    }

    @Test
    void rejectsWrongSignatureAndExpiredTimestamp() {
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = "nonce-123456";
        assertEquals("invalid signature", GatewaySignature.verifyHeader("secret", "GET", "/x", null,
                ts, nonce, new byte[0], "deadbeef"));
        String old = String.valueOf(System.currentTimeMillis() - 10 * 60 * 1000);
        String canonical = GatewaySignature.canonical("GET", "/x", null, old, nonce, new byte[0]);
        assertEquals("timestamp expired", GatewaySignature.verifyHeader("secret", "GET", "/x", null,
                old, nonce, new byte[0], GatewaySignature.hmacHex("secret", canonical)));
    }
}
