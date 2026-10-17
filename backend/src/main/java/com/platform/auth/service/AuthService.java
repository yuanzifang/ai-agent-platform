package com.platform.auth.service;

import com.platform.auth.entity.UserSession;
import com.platform.auth.mapper.UserSessionMapper;
import com.platform.common.exception.BusinessException;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

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

    public record LoginResult(String accessToken, String refreshToken, long expiresIn,
                              String userId, String displayName, boolean platformAdmin, Long tenantId,
                              java.util.List<String> roles, java.util.List<String> permissions) {
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
                user.getIsPlatformAdmin() != null && user.getIsPlatformAdmin() == 1, user.getTenantId(), roles, permissions);
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
