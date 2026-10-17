package com.platform.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.exception.BusinessException;
import com.platform.security.JwtPrincipal;
import com.platform.system.entity.SysRole;
import com.platform.system.entity.SysUser;
import com.platform.system.entity.SysUserRole;
import com.platform.system.entity.UserInvitation;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.mapper.SysUserRoleMapper;
import com.platform.system.mapper.UserInvitationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 成员与邀请管理（需求文档 3.8/6.26）：列表、角色分配（内置角色）、邀请-token 激活。
 */
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private static final Set<String> BUILTIN_ROLES = Set.of("OWNER", "ADMIN", "EDITOR", "MEMBER", "RESTRICTED");

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final UserInvitationMapper invitationMapper;
    private final PasswordEncoder passwordEncoder;

    public List<Map<String, Object>> members(JwtPrincipal principal) {
        List<SysUser> users = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getTenantId, principal.tenantId())
                .orderByAsc(SysUser::getId));
        Map<Long, List<String>> roleMap = new LinkedHashMap<>();
        for (SysUser u : users) {
            roleMap.put(u.getId(), userMapper.findRoleKeys(u.getId()));
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (SysUser u : users) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("displayName", u.getDisplayName());
            m.put("status", u.getStatus());
            m.put("roles", roleMap.getOrDefault(u.getId(), List.of()));
            m.put("lastLoginAt", u.getLastLoginAt());
            m.put("createdAt", u.getCreatedAt());
            list.add(m);
        }
        return list;
    }

    /** 角色整体覆盖：仅允许内置角色 key；不可操作平台管理员账号。 */
    @Transactional
    public void assignRoles(Long userId, List<String> roles, JwtPrincipal principal) {
        SysUser target = userMapper.selectById(userId);
        if (target == null || !principal.tenantId().equals(target.getTenantId())) {
            throw new BusinessException(404, "成员不存在");
        }
        if (target.getIsPlatformAdmin() != null && target.getIsPlatformAdmin() == 1) {
            throw new BusinessException(400, "平台管理员角色不可在此调整");
        }
        for (String r : roles) {
            if (!BUILTIN_ROLES.contains(r)) throw new BusinessException(400, "未知角色：" + r);
        }
        if (roles.contains("OWNER")) throw new BusinessException(400, "所有者角色通过租户转让流程变更");
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        for (String key : roles) {
            SysRole role = roleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                    .eq(SysRole::getRoleKey, key).isNull(SysRole::getTenantId));
            if (role == null) continue;
            SysUserRole ur = new SysUserRole();
            ur.setUserId(userId);
            ur.setRoleId(role.getId());
            userRoleMapper.insert(ur);
        }
    }

    public Map<String, Object> invite(String email, String role, JwtPrincipal principal) {
        if (!BUILTIN_ROLES.contains(role) || "OWNER".equals(role)) {
            throw new BusinessException(400, "角色仅支持 ADMIN/EDITOR/MEMBER/RESTRICTED");
        }
        UserInvitation inv = new UserInvitation();
        inv.setTenantId(principal.tenantId());
        inv.setEmail(email);
        inv.setRole(role);
        inv.setToken(UUID.randomUUID().toString().replace("-", ""));
        inv.setStatus("PENDING");
        inv.setInvitedBy(principal.userId());
        inv.setExpiresAt(LocalDateTime.now().plusDays(7));
        inv.setCreatedAt(LocalDateTime.now());
        invitationMapper.insert(inv);
        return Map.of("invitationId", inv.getId(), "token", inv.getToken(), "expiresAt", inv.getExpiresAt());
    }

    public List<Map<String, Object>> invitations(JwtPrincipal principal) {
        List<UserInvitation> rows = invitationMapper.selectList(new LambdaQueryWrapper<UserInvitation>()
                .eq(UserInvitation::getTenantId, principal.tenantId())
                .orderByDesc(UserInvitation::getId));
        return rows.stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.getId());
            m.put("email", i.getEmail());
            m.put("role", i.getRole());
            m.put("status", "PENDING".equals(i.getStatus()) && i.getExpiresAt().isBefore(LocalDateTime.now())
                    ? "EXPIRED" : i.getStatus());
            m.put("token", i.getToken());
            m.put("expiresAt", i.getExpiresAt());
            m.put("createdAt", i.getCreatedAt());
            return m;
        }).collect(Collectors.toList());
    }

    /** 激活邀请：token 校验 → 建号（本租户）→ 分配预置角色 → 邀请置 ACCEPTED（需求 3.8）。
     * 免登录入口无租户上下文：token 定位邀请后以邀请租户身份执行。 */
    @Transactional
    public Map<String, Object> accept(String token, String username, String password) {
        UserInvitation inv = invitationMapper.findByToken(token);
        if (inv == null || !"PENDING".equals(inv.getStatus()) || inv.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(400, "邀请不存在或已失效");
        }
        if (username == null || username.isBlank() || password == null || password.length() < 8) {
            throw new BusinessException(400, "用户名必填，密码至少 8 位");
        }
        return com.platform.common.context.TenantContext.runWith(inv.getTenantId(), () -> {
        Long existing = userMapper.countByUsernameGlobal(username);
        if (existing != null && existing > 0) {
            throw new BusinessException(400, "用户名已存在");
        }
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(username);
        user.setIsPlatformAdmin(0);
        user.setIsServiceAccount(0);
        user.setTenantId(inv.getTenantId());
        user.setStatus("ENABLED");
        user.setCreatedAt(LocalDateTime.now());
        userMapper.insert(user);

        SysRole role = roleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleKey, inv.getRole()).isNull(SysRole::getTenantId));
        if (role != null) {
            SysUserRole ur = new SysUserRole();
            ur.setUserId(user.getId());
            ur.setRoleId(role.getId());
            userRoleMapper.insert(ur);
        }
        UserInvitation patch = new UserInvitation();
        patch.setId(inv.getId());
        patch.setStatus("ACCEPTED");
        invitationMapper.updateById(patch);
        return Map.of("userId", user.getId());
        });
    }
}
