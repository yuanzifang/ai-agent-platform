package com.platform.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.exception.BusinessException;
import com.platform.security.JwtPrincipal;
import com.platform.system.entity.SysRole;
import com.platform.system.entity.SysUser;
import com.platform.system.entity.SysUserRole;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.mapper.SysUserRoleMapper;
import com.platform.system.service.AuditService;
import com.platform.security.JwtPrincipal;
import com.platform.system.entity.SysTenant;
import com.platform.system.entity.SysTenantConfig;
import com.platform.system.entity.SysTenantQuota;
import com.platform.system.mapper.SysTenantConfigMapper;
import com.platform.system.mapper.SysTenantMapper;
import com.platform.system.mapper.SysTenantQuotaMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 租户配置（需求 3.8/6.40）：信息、配额、默认模型等 config 项。
 */
@Service
@RequiredArgsConstructor
public class TenantAdminService {

    private final SysTenantMapper tenantMapper;
    private final SysTenantQuotaMapper quotaMapper;
    private final ObjectMapper objectMapper;
    private final SysTenantConfigMapper configMapper;
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final AuditService auditService;

    public Map<String, Object> detail(JwtPrincipal principal) {
        SysTenant tenant = tenantMapper.selectById(principal.tenantId());
        if (tenant == null) throw new BusinessException(404, "租户不存在");
        SysTenantQuota quota = quotaMapper.selectOne(new LambdaQueryWrapper<SysTenantQuota>()
                .eq(SysTenantQuota::getTenantId, tenant.getId()));
        Map<String, Object> config = new LinkedHashMap<>();
        for (SysTenantConfig c : configMapper.selectList(new LambdaQueryWrapper<SysTenantConfig>()
                .eq(SysTenantConfig::getTenantId, tenant.getId()))) {
            config.put(c.getConfigKey(), c.getConfigValue());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", tenant.getId());
        result.put("tenantKey", tenant.getTenantKey());
        result.put("name", tenant.getName());
        result.put("status", tenant.getStatus());
        result.put("createdAt", tenant.getCreatedAt());
        result.put("quota", quota == null ? Map.of() : Map.of(
                "maxAgents", quota.getMaxAgents(),
                "maxKnowledgeBases", quota.getMaxKnowledgeBases(),
                "maxTokensPerDay", quota.getMaxTokensPerDay(),
                "maxStorageBytes", quota.getMaxStorageBytes(),
                "maxApiCallsPerMin", quota.getMaxApiCallsPerMin()));
        result.put("config", config);
        return result;
    }

    /** 所有者转让（需求 3.25(6)）：仅当前 OWNER（或平台管理员）可操作；原所有者转为 ADMIN。 */
    @Transactional
    public void transferOwner(Long targetUserId, JwtPrincipal principal) {
        Long tenantId = principal.tenantId();
        if (tenantId == null) throw new BusinessException(400, "平台管理员无租户归属");
        SysUser target = userMapper.selectById(targetUserId);
        if (target == null || !tenantId.equals(target.getTenantId())
                || !"ENABLED".equals(target.getStatus())) {
            throw new BusinessException(400, "目标成员不存在或不可用");
        }
        if (target.getId().equals(principal.userId())) {
            throw new BusinessException(400, "你已经是所有者");
        }
        boolean currentOwner = userMapper.findRoleKeys(principal.userId()).contains("OWNER");
        if (!currentOwner && !principal.platformAdmin()) {
            throw new BusinessException(403, "仅所有者可转让");
        }
        SysRole owner = roleMapper.findBuiltinByKey("OWNER");
        SysRole admin = roleMapper.findBuiltinByKey("ADMIN");
        if (owner == null) throw new BusinessException(500, "内置 OWNER 角色缺失");
        // 目标：清空角色并设为 OWNER
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, target.getId()));
        SysUserRole ur = new SysUserRole();
        ur.setUserId(target.getId());
        ur.setRoleId(owner.getId());
        userRoleMapper.insert(ur);
        // 原所有者：移除 OWNER，补 ADMIN
        if (currentOwner) {
            userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                    .eq(SysUserRole::getUserId, principal.userId())
                    .eq(SysUserRole::getRoleId, owner.getId()));
            if (admin != null && !userMapper.findRoleKeys(principal.userId()).contains("ADMIN")) {
                SysUserRole aur = new SysUserRole();
                aur.setUserId(principal.userId());
                aur.setRoleId(admin.getId());
                userRoleMapper.insert(aur);
            }
        }
        auditService.record(principal.userId(), "tenant.owner.transfer", "tenant",
                String.valueOf(tenantId), "SUCCESS",
                Map.of("from", principal.userId(), "to", target.getId()));
    }

    @Transactional
    public void update(JwtPrincipal principal, Map<String, Object> req) {
        SysTenant tenant = tenantMapper.selectById(principal.tenantId());
        if (tenant == null) throw new BusinessException(404, "租户不存在");
        SysTenant patch = new SysTenant();
        patch.setId(tenant.getId());
        if (req.get("name") instanceof String n && !n.isBlank()) patch.setName(n);
        tenantMapper.updateById(patch);

        if (req.get("config") instanceof Map<?, ?> config) {
            for (Map.Entry<?, ?> e : config.entrySet()) {
                String key = String.valueOf(e.getKey());
                SysTenantConfig existing = configMapper.selectOne(new LambdaQueryWrapper<SysTenantConfig>()
                        .eq(SysTenantConfig::getTenantId, tenant.getId())
                        .eq(SysTenantConfig::getConfigKey, key));
                String value = toJsonValue(e.getValue());
                if (existing == null) {
                    SysTenantConfig c = new SysTenantConfig();
                    c.setTenantId(tenant.getId());
                    c.setConfigKey(key);
                    c.setConfigValue(value);
                    configMapper.insert(c);
                } else {
                    SysTenantConfig c = new SysTenantConfig();
                    c.setId(existing.getId());
                    c.setConfigValue(value);
                    configMapper.updateById(c);
                }
            }
        }
    }

    private String toJsonValue(Object value) {
        if (value instanceof String s) {
            try {
                objectMapper.readTree(s);
                return s;
            } catch (Exception e) {
                try {
                    return objectMapper.writeValueAsString(s);
                } catch (Exception ex) {
                    throw new BusinessException(400, "配置值序列化失败");
                }
            }
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(400, "配置值序列化失败");
        }
    }

    private Object fromJsonValue(String raw) {
        if (raw == null) return null;
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node.isTextual()) return node.asText();
            if (node.isBoolean()) return node.asBoolean();
            if (node.isNumber()) return node.numberValue();
            return node.toString();
        } catch (Exception e) {
            return raw;
        }
    }
}
