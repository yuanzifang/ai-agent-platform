package com.platform.gateway.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** 网关请求签名与时间窗/nonce 校验。 */
public final class GatewaySignature {

    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final long MAX_SKEW_MS = 5 * 60 * 1000L;

    private GatewaySignature() {
    }

    public static String canonical(String method, String path, String query,
                                    String timestamp, String nonce, byte[] body) {
        return (method == null ? "" : method.toUpperCase()) + "\n"
                + (path == null ? "" : path) + "\n"
                + (query == null ? "" : query) + "\n"
                + (timestamp == null ? "" : timestamp) + "\n"
                + (nonce == null ? "" : nonce) + "\n"
                + sha256Hex(body);
    }

    /** 返回错误原因；null 表示签名头和签名本身有效（nonce 唯一性由调用方检查）。 */
    public static String verifyHeader(String secret, String method, String path, String query,
                                      String timestamp, String nonce, byte[] body, String signature) {
        if (secret == null || secret.isBlank()) return "signature not configured";
        if (timestamp == null || timestamp.isBlank()) return "missing timestamp";
        if (nonce == null || !NONCE.matcher(nonce).matches()) return "invalid nonce";
        if (signature == null || signature.isBlank()) return "missing signature";
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return "invalid timestamp";
        }
        if (Math.abs(System.currentTimeMillis() - ts) > MAX_SKEW_MS) return "timestamp expired";
        String expected = hmacHex(secret, canonical(method, path, query, timestamp, nonce, body));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            return "invalid signature";
        }
        return null;
    }

    public static boolean nonceTtlValid(String nonce) {
        return nonce != null && NONCE.matcher(nonce).matches();
    }

    public static Duration nonceTtl() {
        return Duration.ofMinutes(10);
    }

    public static String sha256Hex(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body == null ? new byte[0] : body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hmacHex(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
