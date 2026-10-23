package com.platform.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.exception.BusinessException;
import com.platform.security.JwtPrincipal;
import com.platform.system.entity.SysRole;
import com.platform.system.entity.SysTenant;
import com.platform.system.entity.SysTenantQuota;
import com.platform.system.entity.SysUser;
import com.platform.system.entity.SysUserRole;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.system.mapper.SysTenantMapper;
import com.platform.system.mapper.SysTenantQuotaMapper;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台管理（需求 3.8）：租户开通（含 OWNER 账号与默认配额）、启停、列表。
 */
@Service
@RequiredArgsConstructor
public class PlatformAdminService {

    private final SysTenantMapper tenantMapper;
    private final SysTenantQuotaMapper quotaMapper;
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public List<Map<String, Object>> tenants() {
        List<SysTenant> tenants = tenantMapper.selectList(null);
        List<Map<String, Object>> list = new ArrayList<>();
        for (SysTenant t : tenants) {
            Long members = com.platform.common.context.TenantContext.runWith(t.getId(), () ->
                    userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                            .eq(SysUser::getTenantId, t.getId())));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("tenantKey", t.getTenantKey());
            m.put("name", t.getName());
            m.put("status", t.getStatus());
            m.put("members", members);
            m.put("createdAt", t.getCreatedAt());
            list.add(m);
        }
        return list;
    }

    /** 开通租户：建租户 + 默认配额 + OWNER 账号（需求 3.8(3)）。 */
    @Transactional
    public Map<String, Object> createTenant(String tenantKey, String name,
                                            String ownerUsername, String ownerPassword) {
        Long dup = tenantMapper.selectCount(new LambdaQueryWrapper<SysTenant>()
                .eq(SysTenant::getTenantKey, tenantKey));
        if (dup != null && dup > 0) throw new BusinessException(400, "租户标识已存在");
        Long userDup = userMapper.countByUsernameGlobal(ownerUsername);
        if (userDup != null && userDup > 0) throw new BusinessException(400, "所有者用户名已存在");
        if (ownerPassword == null || ownerPassword.length() < 8) {
            throw new BusinessException(400, "密码至少 8 位");
        }
        SysTenant t = new SysTenant();
        t.setTenantKey(tenantKey);
        t.setName(name);
        t.setStatus("ACTIVE");
        t.setCreatedAt(LocalDateTime.now());
        tenantMapper.insert(t);

        return com.platform.common.context.TenantContext.runWith(t.getId(), () -> {
        SysTenantQuota q = new SysTenantQuota();
        q.setTenantId(t.getId());
        q.setMaxAgents(10);
        q.setMaxKnowledgeBases(5);
        q.setMaxTokensPerDay(1_000_000L);
        q.setMaxStorageBytes(10_737_418_240L);
        q.setMaxApiCallsPerMin(60);
        quotaMapper.insert(q);

        SysUser owner = new SysUser();
        owner.setUsername(ownerUsername);
        owner.setPasswordHash(passwordEncoder.encode(ownerPassword));
        owner.setDisplayName(name + " 所有者");
        owner.setIsPlatformAdmin(0);
        owner.setIsServiceAccount(0);
        owner.setTenantId(t.getId());
        owner.setStatus("ENABLED");
        owner.setCreatedAt(LocalDateTime.now());
        userMapper.insert(owner);

        SysRole ownerRole = roleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleKey, "OWNER").isNull(SysRole::getTenantId));
        if (ownerRole != null) {
            SysUserRole ur = new SysUserRole();
            ur.setUserId(owner.getId());
            ur.setRoleId(ownerRole.getId());
            userRoleMapper.insert(ur);
        }
        auditService.record(owner.getId(), "platform.createTenant", "tenant",
                String.valueOf(t.getId()), "SUCCESS", Map.of("tenantKey", tenantKey));
        return Map.of("tenantId", t.getId(), "ownerUserId", owner.getId());
        });
    }

    /** 当前配额（无记录时返回默认值，供编辑弹窗预填）。平台管理员无租户上下文，显式还原。 */
    public SysTenantQuota quotaOf(Long tenantId) {
        return com.platform.common.context.TenantContext.runWith(tenantId, () -> {
        SysTenantQuota q = quotaMapper.selectOne(new LambdaQueryWrapper<SysTenantQuota>()
                .eq(SysTenantQuota::getTenantId, tenantId));
        if (q != null) return q;
        SysTenantQuota def = new SysTenantQuota();
        def.setTenantId(tenantId);
        def.setMaxAgents(10);
        def.setMaxKnowledgeBases(5);
        def.setMaxTokensPerDay(1_000_000L);
        def.setMaxStorageBytes(10_737_418_240L);
        def.setMaxApiCallsPerMin(60);
        return def;
        });
    }

    /** 配额调整（需求 3.8 租户管理核心动作；此前只能改库）。 */
    public void updateQuota(Long tenantId, SysTenantQuota req) {
        if (tenantMapper.selectById(tenantId) == null) throw new BusinessException(404, "租户不存在");
        if (req.getMaxAgents() == null || req.getMaxAgents() < 0
                || req.getMaxKnowledgeBases() == null || req.getMaxKnowledgeBases() < 0
                || req.getMaxTokensPerDay() == null || req.getMaxTokensPerDay() < 0
                || req.getMaxStorageBytes() == null || req.getMaxStorageBytes() < 0
                || req.getMaxApiCallsPerMin() == null || req.getMaxApiCallsPerMin() < 0) {
            throw new BusinessException(400, "配额必须为非负整数");
        }
        com.platform.common.context.TenantContext.run(tenantId, () -> {
            SysTenantQuota existing = quotaMapper.selectOne(new LambdaQueryWrapper<SysTenantQuota>()
                    .eq(SysTenantQuota::getTenantId, tenantId));
            req.setTenantId(tenantId);
            if (existing == null) {
                quotaMapper.insert(req);
            } else {
                req.setId(existing.getId());
                quotaMapper.updateById(req);
            }
        });
    }

    public void updateStatus(Long tenantId, String status, Long operatorId) {
        if (!List.of("TRIAL", "ACTIVE", "SUSPENDED", "CLOSED").contains(status)) {
            throw new BusinessException(400, "非法状态");
        }
        com.platform.common.context.TenantContext.run(tenantId, () ->
                auditService.record(operatorId, "platform.tenantStatus", "tenant",
                        String.valueOf(tenantId), "SUCCESS", Map.of("status", status)));
        SysTenant patch = new SysTenant();
        patch.setId(tenantId);
        patch.setStatus(status);
        tenantMapper.updateById(patch);
    }

    public void requirePlatformAdmin(JwtPrincipal principal) {
        if (!principal.platformAdmin()) {
            throw new BusinessException(403, "仅平台管理员可访问");
        }
    }
}
