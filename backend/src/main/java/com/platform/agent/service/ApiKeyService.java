package com.platform.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.entity.IntegrationApiKey;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.agent.mapper.IntegrationApiKeyMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.security.JwtPrincipal;
import com.platform.system.entity.SysUser;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Agent 集成 API Key（需求 3.2(4) / 3.9 / 数据字典 6.14）：
 * 归属 Agent、创建时仅返回一次明文、可禁用/删除、渠道与日/分钟限额；Key 对应独立服务账号。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final IntegrationApiKeyMapper keyMapper;
    private final AgentRegistryMapper agentMapper;
    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;
    private final AuditService auditService;

    public Map<String, Object> list(Long agentId) {
        AgentRegistry agent = agent(agentId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (IntegrationApiKey k : keyMapper.selectList(new LambdaQueryWrapper<IntegrationApiKey>()
                .eq(IntegrationApiKey::getAgentId, agentId)
                .orderByDesc(IntegrationApiKey::getId))) {
            rows.add(view(k));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("list", rows);
        m.put("total", rows.size());
        m.put("agentId", agentId);
        m.put("agentName", agent.getName());
        return m;
    }

    @Transactional
    public Map<String, Object> create(Long agentId, Map<String, Object> req, JwtPrincipal principal) {
        AgentRegistry agent = agent(agentId);
        long count = keyMapper.selectCount(new LambdaQueryWrapper<IntegrationApiKey>()
                .eq(IntegrationApiKey::getAgentId, agentId));
        if (count >= 10) throw new BusinessException(400, "每个 Agent 最多创建 10 个 API Key");
        String name = str(req.get("name"));
        if (name == null || name.isBlank()) throw new BusinessException(400, "Key 名称不能为空");
        String channel = normalizeChannel(req.get("channel"));
        int dailyLimit = nonNegative(req.get("dailyLimit"), "日调用上限");
        int minuteLimit = nonNegative(req.get("minuteLimit"), "分钟调用上限");

        String rawKey = "sk-ag-" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String hash = sha256(rawKey);

        SysUser svc = new SysUser();
        svc.setUsername("svc_" + hash.substring(0, 12));
        svc.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        svc.setDisplayName("API Key 服务账号：" + name.trim());
        svc.setIsPlatformAdmin(0);
        svc.setIsServiceAccount(1);
        svc.setTenantId(principal.tenantId());
        svc.setStatus("ENABLED");
        svc.setCreatedAt(LocalDateTime.now());
        userMapper.insert(svc);

        IntegrationApiKey key = new IntegrationApiKey();
        key.setAgentId(agentId);
        key.setName(name.trim());
        key.setChannel(channel);
        key.setKeyHash(hash);
        key.setKeyPrefix(rawKey.substring(0, 12));
        key.setServiceUserId(svc.getId());
        key.setStatus("ENABLED");
        key.setDailyLimit(dailyLimit);
        key.setMinuteLimit(minuteLimit);
        key.setTenantId(principal.tenantId());
        key.setCreatedAt(LocalDateTime.now());
        keyMapper.insert(key);

        auditService.record(principal.userId(), "api_key.create", "agent", String.valueOf(agentId),
                "SUCCESS", Map.of("keyId", key.getId(), "name", key.getName(), "channel", channel));
        Map<String, Object> result = view(key);
        result.put("apiKey", rawKey); // 仅本次返回明文
        result.put("endpoint", "/api/v1/open/chat/completions");
        result.put("agentName", agent.getName());
        return result;
    }

    @Transactional
    public void update(Long agentId, Long keyId, Map<String, Object> req, JwtPrincipal principal) {
        IntegrationApiKey key = key(agentId, keyId);
        if (req.containsKey("name")) {
            String name = str(req.get("name"));
            if (name == null || name.isBlank()) throw new BusinessException(400, "Key 名称不能为空");
            key.setName(name.trim());
        }
        if (req.containsKey("channel")) key.setChannel(normalizeChannel(req.get("channel")));
        if (req.containsKey("status")) {
            String status = String.valueOf(req.get("status")).toUpperCase(Locale.ROOT);
            if (!List.of("ENABLED", "DISABLED").contains(status)) throw new BusinessException(400, "非法状态");
            key.setStatus(status);
        }
        if (req.containsKey("dailyLimit")) key.setDailyLimit(nonNegative(req.get("dailyLimit"), "日调用上限"));
        if (req.containsKey("minuteLimit")) key.setMinuteLimit(nonNegative(req.get("minuteLimit"), "分钟调用上限"));
        keyMapper.updateById(key);
        auditService.record(principal.userId(), "api_key.update", "agent", String.valueOf(key.getAgentId()),
                "SUCCESS", Map.of("keyId", keyId, "status", key.getStatus()));
    }

    @Transactional
    public void delete(Long agentId, Long keyId, JwtPrincipal principal) {
        IntegrationApiKey key = key(agentId, keyId);
        keyMapper.deleteById(keyId);
        if (key.getServiceUserId() != null) {
            long refs = keyMapper.selectCount(new LambdaQueryWrapper<IntegrationApiKey>()
                    .eq(IntegrationApiKey::getServiceUserId, key.getServiceUserId()));
            if (refs == 0) {
                userMapper.deleteById(key.getServiceUserId());
            }
        }
        auditService.record(principal.userId(), "api_key.delete", "agent", String.valueOf(agentId),
                "SUCCESS", Map.of("keyId", keyId));
    }

    /**
     * 外部鉴权：校验哈希/状态/Agent 发布状态/限额，更新 last_used；返回 Key（含 tenant/agent）。
     */
    public IntegrationApiKey authenticate(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) throw new BusinessException(401, "缺少 API Key（X-Api-Key）");
        IntegrationApiKey key = keyMapper.findByHashIgnore(sha256(rawKey.trim()));
        if (key == null) throw new BusinessException(401, "API Key 无效");
        if (!"ENABLED".equals(key.getStatus())) throw new BusinessException(403, "API Key 已禁用");
        AgentRegistry agent = TenantContext.runWith(key.getTenantId(), () -> agentMapper.selectById(key.getAgentId()));
        if (agent == null || "DELETED".equals(agent.getStatus())) throw new BusinessException(404, "Agent 不存在");
        if (!"PUBLISHED".equals(agent.getStatus())) throw new BusinessException(403, "Agent 未发布，无法调用");
        checkLimits(key);
        keyMapper.touch(key.getId(), LocalDateTime.now());
        key.setLastUsedAt(LocalDateTime.now());
        return key;
    }

    /** 日/分钟调用限额（Redis 计数，0=不限；Redis 异常时放行并记录日志）。 */
    private void checkLimits(IntegrationApiKey key) {
        try {
            long minute = System.currentTimeMillis() / 60000;
            if (key.getMinuteLimit() != null && key.getMinuteLimit() > 0) {
                String mk = "apikey:min:" + key.getId() + ":" + minute;
                Long c = redis.opsForValue().increment(mk);
                redis.expire(mk, 2, TimeUnit.MINUTES);
                if (c != null && c > key.getMinuteLimit()) {
                    throw new BusinessException(429, "API Key 分钟调用上限已超限（" + key.getMinuteLimit() + " 次/分钟）");
                }
            }
            if (key.getDailyLimit() != null && key.getDailyLimit() > 0) {
                String day = java.time.LocalDate.now().toString().replace("-", "");
                String dk = "apikey:day:" + key.getId() + ":" + day;
                Long c = redis.opsForValue().increment(dk);
                redis.expire(dk, 26, TimeUnit.HOURS);
                if (c != null && c > key.getDailyLimit()) {
                    throw new BusinessException(429, "API Key 日调用上限已超限（" + key.getDailyLimit() + " 次/天）");
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("API Key 限额检查失败（放行）：key={} err={}", key.getId(), e.getMessage());
        }
    }

    // ---------- helpers ----------

    private AgentRegistry agent(Long agentId) {
        AgentRegistry agent = agentMapper.selectById(agentId);
        if (agent == null || "DELETED".equals(agent.getStatus())) throw new BusinessException(404, "Agent 不存在");
        return agent;
    }

    private IntegrationApiKey key(Long agentId, Long keyId) {
        IntegrationApiKey key = keyMapper.selectById(keyId);
        if (key == null || !key.getAgentId().equals(agentId)) throw new BusinessException(404, "API Key 不存在");
        return key;
    }

    private Map<String, Object> view(IntegrationApiKey k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.getId());
        m.put("agentId", k.getAgentId());
        m.put("name", k.getName());
        m.put("channel", k.getChannel());
        m.put("keyPrefix", k.getKeyPrefix() + "********");
        m.put("status", k.getStatus());
        m.put("dailyLimit", k.getDailyLimit());
        m.put("minuteLimit", k.getMinuteLimit());
        m.put("lastUsedAt", k.getLastUsedAt());
        m.put("createdAt", k.getCreatedAt());
        return m;
    }

    private String normalizeChannel(Object raw) {
        String channel = raw == null ? "API" : String.valueOf(raw).toUpperCase(Locale.ROOT);
        if (!List.of("WEB", "API", "WIDGET", "THIRD_PARTY").contains(channel)) {
            throw new BusinessException(400, "调用渠道仅支持 WEB/API/THIRD_PARTY");
        }
        return channel;
    }

    private int nonNegative(Object v, String label) {
        if (v == null || String.valueOf(v).isBlank()) return 0;
        try {
            int i = Integer.parseInt(String.valueOf(v));
            if (i < 0) throw new BusinessException(400, label + "不能为负数（0=不限制）");
            return i;
        } catch (NumberFormatException e) {
            throw new BusinessException(400, label + "格式错误");
        }
    }

    private String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("哈希失败", e);
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
