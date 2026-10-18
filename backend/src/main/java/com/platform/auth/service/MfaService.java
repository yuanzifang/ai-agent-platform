package com.platform.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.exception.BusinessException;
import com.platform.common.security.CryptoService;
import com.platform.system.entity.MfaRecoveryCode;
import com.platform.system.entity.SysUser;
import com.platform.system.mapper.MfaRecoveryCodeMapper;
import com.platform.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * MFA（TOTP，需求 3.25(5)）：密钥加密存储、6 位动态码（±1 窗口）、10 个一次性恢复码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MfaService {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int STEP_SECONDS = 30;

    private final SysUserMapper userMapper;
    private final MfaRecoveryCodeMapper recoveryMapper;
    private final CryptoService cryptoService;

    public synchronized void setup(Long userId) {
        SysUser user = user(userId);
        if (user.getMfaEnabled() != null && user.getMfaEnabled() == 1) {
            throw new BusinessException(400, "MFA 已启用，如需重置请先关闭");
        }
        user.setMfaSecret(cryptoService.encrypt(randomSecret()));
        user.setMfaEnabled(0);
        userMapper.updateById(user);
    }

    public String secret(Long userId) {
        SysUser user = user(userId);
        if (user.getMfaSecret() == null || user.getMfaSecret().isBlank()) {
            throw new BusinessException(400, "请先初始化 MFA");
        }
        return cryptoService.decrypt(user.getMfaSecret());
    }

    @Transactional
    public List<String> enable(Long userId, String code) {
        SysUser user = user(userId);
        String secret = secret(userId);
        if (!verifyTotp(secret, code)) throw new BusinessException(400, "动态验证码错误");
        user.setMfaEnabled(1);
        userMapper.updateById(user);
        recoveryMapper.deleteByUser(userId);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String raw = randomRecovery();
            codes.add(raw);
            MfaRecoveryCode row = new MfaRecoveryCode();
            row.setUserId(userId);
            row.setCodeHash(sha256(normalizeRecovery(raw)));
            row.setUsed(0);
            row.setTenantId(user.getTenantId());
            row.setCreatedAt(java.time.LocalDateTime.now());
            recoveryMapper.insert(row);
        }
        return codes;
    }

    @Transactional
    public void disable(Long userId, String code) {
        SysUser user = user(userId);
        if (user.getMfaEnabled() == null || user.getMfaEnabled() == 0) {
            throw new BusinessException(400, "MFA 未启用");
        }
        verifyLoginCode(userId, code);
        user.setMfaEnabled(0);
        user.setMfaSecret(null);
        userMapper.updateById(user);
        recoveryMapper.deleteByUser(userId);
    }

    /** 登录二次校验：动态码或一次性恢复码（恢复码使用后作废）。 */
    @Transactional
    public void verifyLoginCode(Long userId, String code) {
        SysUser user = user(userId);
        if (user.getMfaEnabled() == null || user.getMfaEnabled() == 0) return;
        if (code == null || code.isBlank()) throw new BusinessException(401, "请输入 MFA 验证码");
        String secret = user.getMfaSecret() == null ? null : cryptoService.decrypt(user.getMfaSecret());
        if (secret != null && verifyTotp(secret, code)) return;
        MfaRecoveryCode recovery = recoveryMapper.findUnused(userId, sha256(normalizeRecovery(code)));
        if (recovery == null) throw new BusinessException(401, "MFA 验证码错误");
        recoveryMapper.markUsed(recovery.getId());
    }

    public boolean enabled(Long userId) {
        SysUser user = user(userId);
        return user.getMfaEnabled() != null && user.getMfaEnabled() == 1;
    }

    public long recoveryCodesLeft(Long userId) {
        return recoveryMapper.countUnused(userId);
    }

    // ---------- TOTP ----------

    public boolean verifyTotp(String secret, String code) {
        if (code == null) return false;
        String normalized = code.replaceAll("\\s", "");
        if (!normalized.matches("\\d{6}")) return false;
        long counter = Instant.now().getEpochSecond() / STEP_SECONDS;
        for (long offset = -1; offset <= 1; offset++) {
            if (normalized.equals(totp(secret, counter + offset))) return true;
        }
        return false;
    }

    public String totp(String secret, long counter) {
        try {
            byte[] key = base32Decode(secret);
            byte[] data = new byte[8];
            long value = counter;
            for (int i = 7; i >= 0; i--) {
                data[i] = (byte) (value & 0xff);
                value >>= 8;
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP 计算失败", e);
        }
    }

    public String otpauthUri(String username, String secret) {
        String label = java.net.URLEncoder.encode("AI Agent Platform:" + username, StandardCharsets.UTF_8);
        return "otpauth://totp/" + label + "?secret=" + secret
                + "&issuer=AI%20Agent%20Platform&algorithm=SHA1&digits=6&period=30";
    }

    // ---------- helpers ----------

    private SysUser user(Long userId) {
        SysUser user = userMapper.findByIdIgnore(userId);
        if (user == null) throw new BusinessException(404, "用户不存在");
        return user;
    }

    private String randomSecret() {
        byte[] bytes = new byte[20];
        new SecureRandom().nextBytes(bytes);
        return base32Encode(bytes);
    }

    private String randomRecovery() {
        byte[] bytes = new byte[5];
        new SecureRandom().nextBytes(bytes);
        String hex = HexFormat.of().formatHex(bytes).toUpperCase(Locale.ROOT);
        return hex.substring(0, 5) + "-" + hex.substring(5);
    }

    private String normalizeRecovery(String code) {
        return code == null ? "" : code.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    private byte[] base32Decode(String input) {
        String s = input.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
        int buffer = 0, bits = 0;
        List<Byte> out = new ArrayList<>();
        for (char c : s.toCharArray()) {
            int v = BASE32.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("非法 Base32 字符");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.add((byte) ((buffer >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        byte[] bytes = new byte[out.size()];
        for (int i = 0; i < out.size(); i++) bytes[i] = out.get(i);
        return bytes;
    }
}
