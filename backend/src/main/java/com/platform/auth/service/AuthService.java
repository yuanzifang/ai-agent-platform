package com.platform.auth.service;

import com.platform.auth.entity.PasswordResetToken;
import com.platform.auth.entity.UserSession;
import com.platform.auth.mapper.PasswordResetTokenMapper;
import com.platform.auth.mapper.UserSessionMapper;
import com.platform.common.exception.BusinessException;
import com.platform.common.mail.MailService;
import com.platform.security.JwtPrincipal;
import com.platform.security.JwtService;
import com.platform.system.entity.AuditLogin;
import com.platform.system.entity.SysTenant;
import com.platform.system.entity.SysUser;
import com.platform.system.mapper.AuditLoginMapper;
import com.platform.system.mapper.SysTenantMapper;
import com.platform.system.mapper.SysUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 平台管理员直接持有全部权限点（需求 3.5）。 */
    private static final List<String> ALL_PERMISSIONS = List.of(
            "agent:create", "agent:edit", "agent:delete", "agent:publish",
            "kb:create", "kb:edit", "kb:delete", "model:manage", "eval:manage",
            "billing:read", "audit:read", "tenant:manage", "user:manage",
            "prompt:manage", "tool:manage", "skill:manage");

    private final SysUserMapper userMapper;
    private final SysTenantMapper tenantMapper;
    private final UserSessionMapper sessionMapper;
    private final AuditLoginMapper auditLoginMapper;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final MfaService mfaService;
    private final PasswordResetTokenMapper resetTokenMapper;
    private final MailService mailService;
    @org.springframework.beans.factory.annotation.Value("${app.frontend-base:http://localhost:5173}")
    private String frontendBase;

    public record LoginResult(String accessToken, String refreshToken, long expiresIn,
                              String userId, String displayName, boolean platformAdmin, Long tenantId,
                              java.util.List<String> roles, java.util.List<String> permissions,
                              boolean mfaRequired, String mfaToken) {
    }

    public LoginResult login(String username, String password, String tenantKey,
                             HttpServletRequest request) {
        SysUser user = userMapper.findByUsername(username);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            audit(user, "FAIL", request);
            throw new BusinessException(401, "用户名或密码错误");
        }
        if (!"ENABLED".equals(user.getStatus())) {
            throw new BusinessException(403, "账号已禁用");
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            throw new BusinessException(423, "账号已锁定，请稍后再试");
        }
        // 租户校验：tenantKey 指向的租户须与账号归属一致（平台管理员 tenantId 为空，可携带任意 tenantKey 登录后选择）
        if (tenantKey != null && !tenantKey.isBlank() && user.getTenantId() != null) {
            SysTenant tenant = tenantMapper.findByKey(tenantKey);
            if (tenant == null || !tenant.getId().equals(user.getTenantId())) {
                throw new BusinessException(403, "账号不属于该租户");
            }
        }

        // MFA：第一步校验密码成功，签发 5 分钟短期凭证，待动态码校验后再发正式 Token
        if (user.getMfaEnabled() != null && user.getMfaEnabled() == 1) {
            return new LoginResult(null, null, 0, null, null,
                    user.getIsPlatformAdmin() != null && user.getIsPlatformAdmin() == 1,
                    user.getTenantId(), null, null, true, jwtService.createMfaToken(user.getId()));
        }
        return issueTokens(user, request);
    }

    /** MFA 第二步：校验短期凭证 + 动态码/恢复码，签发正式 Token。 */
    public LoginResult verifyMfa(String mfaToken, String code, HttpServletRequest request) {
        Long userId = jwtService.parseMfaToken(mfaToken);
        if (userId == null) throw new BusinessException(401, "MFA 凭证无效或已过期，请重新登录");
        SysUser user = userMapper.findByIdIgnore(userId);
        if (user == null || !"ENABLED".equals(user.getStatus())) throw new BusinessException(401, "账号不可用");
        mfaService.verifyLoginCode(userId, code);
        return issueTokens(user, request);
    }

    public Map<String, Object> mfaStatus(Long userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", mfaService.enabled(userId));
        m.put("recoveryCodesLeft", mfaService.recoveryCodesLeft(userId));
        return m;
    }

    public Map<String, Object> mfaSetup(Long userId) {
        SysUser user = userMapper.findByIdIgnore(userId);
        if (user == null) throw new BusinessException(404, "用户不存在");
        mfaService.setup(userId);
        String secret = mfaService.secret(userId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("secret", secret);
        m.put("otpauthUri", mfaService.otpauthUri(user.getUsername(), secret));
        return m;
    }

    public List<String> mfaEnable(Long userId, String code) {
        return mfaService.enable(userId, code);
    }

    public void mfaDisable(Long userId, String code) {
        mfaService.disable(userId, code);
    }

    /** 自助修改密码（需求 3.25(3) P0）：校验旧密码、强度、更新后踢出所有刷新会话。 */
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        SysUser user = userMapper.findByIdIgnore(userId);
        if (user == null) throw new BusinessException(404, "用户不存在");
        if (oldPassword == null || !passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BusinessException(400, "原密码错误");
        }
        validatePassword(newPassword);
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException(400, "新密码不能与原密码相同");
        }
        userMapper.updatePasswordIgnore(userId, passwordEncoder.encode(newPassword), LocalDateTime.now());
        sessionMapper.revokeAllByUser(userId);
    }

    /** 找回密码第一步：生成一次性 Token（30 分钟）；开发环境未配置邮件时写入日志。 */
    public void requestPasswordReset(String username) {
        if (username == null || username.isBlank()) return;
        SysUser user = userMapper.findByUsername(username.trim());
        if (user == null || !"ENABLED".equals(user.getStatus())) return; // 防枚举：统一返回成功
        String token = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        PasswordResetToken row = new PasswordResetToken();
        row.setUserId(user.getId());
        row.setTokenHash(jwtService.hash(token));
        row.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        row.setUsed(0);
        row.setTenantId(user.getTenantId());
        row.setCreatedAt(LocalDateTime.now());
        resetTokenMapper.insert(row);
        String link = frontendBase.replaceAll("/+$", "") + "/reset-password?token=" + token;
        boolean sent = user.getUsername() != null && user.getUsername().contains("@")
                && mailService.send(user.getUsername(), "AI Agent 平台 - 重置密码",
                "请在 30 分钟内打开以下链接重置密码：\n" + link + "\n如非本人操作请忽略。");
        if (!sent) {
            // 未配置 SMTP 或发送失败：写入日志，便于管理员/开发环境使用
            log.info("密码重置链接（30 分钟有效）：{}", link);
        }
    }

    /** 找回密码第二步：Token 校验 + 重置密码 + 踢出会话 + Token 作废。 */
    public void resetPassword(String token, String newPassword) {
        if (token == null || token.isBlank()) throw new BusinessException(400, "重置链接无效");
        PasswordResetToken row = resetTokenMapper.findByHashIgnore(jwtService.hash(token.trim()));
        if (row == null || row.getUsed() != null && row.getUsed() == 1
                || row.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(400, "重置链接无效或已过期");
        }
        validatePassword(newPassword);
        SysUser user = userMapper.findByIdIgnore(row.getUserId());
        if (user == null) throw new BusinessException(404, "用户不存在");
        userMapper.updatePasswordIgnore(user.getId(), passwordEncoder.encode(newPassword), LocalDateTime.now());
        resetTokenMapper.markUsedIgnore(row.getId());
        sessionMapper.revokeAllByUser(user.getId());
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new BusinessException(400, "密码至少 8 位");
        }
        if (password.length() > 64) throw new BusinessException(400, "密码过长");
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) throw new BusinessException(400, "密码需同时包含字母和数字");
    }

    private LoginResult issueTokens(SysUser user, HttpServletRequest request) {
        List<String> roles = user.getIsPlatformAdmin() != null && user.getIsPlatformAdmin() == 1
                ? List.of("PLATFORM_ADMIN")
                : userMapper.findRoleKeys(user.getId());
        List<String> permissions = user.getIsPlatformAdmin() != null && user.getIsPlatformAdmin() == 1
                ? ALL_PERMISSIONS : userMapper.findPermKeys(user.getId());

        String accessToken = jwtService.createAccessToken(
                new JwtPrincipal(user.getId(), user.getTenantId(),
                        user.getIsPlatformAdmin() != null && user.getIsPlatformAdmin() == 1,
                        roles, permissions));
        String refreshToken = jwtService.createRefreshToken();

        UserSession session = new UserSession();
        session.setUserId(user.getId());
        session.setRefreshTokenHash(jwtService.hash(refreshToken));
        session.setDevice(truncate(request.getHeader("User-Agent"), 128));
        session.setIp(truncate(clientIp(request), 64));
        session.setExpiresAt(LocalDateTime.now().plusDays(7));
        session.setRevoked(0);
        session.setCreatedAt(LocalDateTime.now());
        sessionMapper.insert(session);

        userMapper.touchLastLogin(user.getId());
        audit(user, "SUCCESS", request);

        return new LoginResult(accessToken, refreshToken, jwtService.getExpirySeconds(),
                String.valueOf(user.getId()), user.getDisplayName(),
                user.getIsPlatformAdmin() != null && user.getIsPlatformAdmin() == 1,
                user.getTenantId(), roles, permissions, false, null);
    }

    /** SSO 登录：外部身份已校验，直接签发平台 Token（不校验密码/MFA）。 */
    public LoginResult loginAs(Long userId, HttpServletRequest request) {
        SysUser user = userMapper.findByIdIgnore(userId);
        if (user == null || !"ENABLED".equals(user.getStatus())) {
            throw new BusinessException(403, "账号不可用");
        }
        return issueTokens(user, request);
    }

    public String refresh(String refreshToken) {
        UserSession session = sessionMapper.findValidByHash(jwtService.hash(refreshToken));
        if (session == null || session.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(401, "刷新令牌无效或已过期");
        }
        SysUser user = userMapper.findByIdIgnore(session.getUserId());
        if (user == null || !"ENABLED".equals(user.getStatus())) {
            throw new BusinessException(401, "账号不可用");
        }
        List<String> roles = user.getIsPlatformAdmin() != null && user.getIsPlatformAdmin() == 1
                ? List.of("PLATFORM_ADMIN")
                : userMapper.findRoleKeys(user.getId());
        List<String> permissions = user.getIsPlatformAdmin() != null && user.getIsPlatformAdmin() == 1
                ? ALL_PERMISSIONS : userMapper.findPermKeys(user.getId());
        return jwtService.createAccessToken(new JwtPrincipal(user.getId(), user.getTenantId(),
                user.getIsPlatformAdmin() != null && user.getIsPlatformAdmin() == 1, roles, permissions));
    }

    public void logout(String refreshToken) {
        sessionMapper.revokeByHash(jwtService.hash(refreshToken));
    }

    private void audit(SysUser user, String result, HttpServletRequest request) {
        AuditLogin audit = new AuditLogin();
        audit.setTenantId(user == null ? null : user.getTenantId());
        audit.setUserId(user == null ? null : user.getId());
        audit.setResult(result);
        audit.setIp(truncate(clientIp(request), 64));
        audit.setUserAgent(truncate(request.getHeader("User-Agent"), 256));
        audit.setCreatedAt(LocalDateTime.now());
        auditLoginMapper.insert(audit);
    }

    /** 截断防溢出：浏览器 UA 可超 500 字符，超出列宽会导致插入失败。 */
    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }
}
