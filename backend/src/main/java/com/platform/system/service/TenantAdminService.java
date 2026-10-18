package com.platform.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.exception.BusinessException;
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
