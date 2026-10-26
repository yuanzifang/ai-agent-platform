package com.platform.deploy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.common.exception.BusinessException;
import com.platform.deploy.entity.AgentRuntimeKey;
import com.platform.deploy.mapper.AgentRuntimeKeyMapper;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 部署容器的调用方 Key：按 Key 日配额、可停用/删除。
 * 明文只在创建时返回一次；容器里只注入 SHA-256 哈希（CALLER_KEYS_JSON）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRuntimeKeyService {

    private final AgentRuntimeKeyMapper keyMapper;
    private final AgentRegistryMapper agentMapper;
    private final AuditService auditService;

    public List<Map<String, Object>> list(Long agentId) {
        requireAgent(agentId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentRuntimeKey key : keyMapper.selectList(new LambdaQueryWrapper<AgentRuntimeKey>()
                .eq(AgentRuntimeKey::getAgentId, agentId)
                .orderByDesc(AgentRuntimeKey::getId))) {
            rows.add(view(key));
        }
        return rows;
    }

    @Transactional
    public Map<String, Object> create(Long agentId, Map<String, Object> req, JwtPrincipal principal) {
        AgentRegistry agent = requireAgent(agentId);
        long count = keyMapper.selectCount(new LambdaQueryWrapper<AgentRuntimeKey>()
                .eq(AgentRuntimeKey::getAgentId, agentId));
        if (count >= 20) throw new BusinessException(400, "每个 Agent 最多创建 20 个调用方 Key");

        String name = str(req.get("name"));
        if (name == null || name.isBlank()) throw new BusinessException(400, "Key 名称不能为空");
        if (name.trim().length() > 64) throw new BusinessException(400, "Key 名称不能超过 64 个字符");
        int quota = nonNegative(req.get("dailyQuota"));

        String rawKey = "sk-run-" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        AgentRuntimeKey key = new AgentRuntimeKey();
        key.setAgentId(agentId);
        key.setTenantId(agent.getTenantId());
        key.setName(name.trim());
        key.setKeyHash(sha256(rawKey));
        key.setKeyPrefix(rawKey.substring(0, 12));
        key.setDailyQuota(quota);
        key.setStatus("ENABLED");
        key.setCreatedBy(principal == null ? null : principal.userId());
        key.setCreatedAt(LocalDateTime.now());
        keyMapper.insert(key);

        auditService.record(principal.userId(), "runtime_key.create", "agent", String.valueOf(agentId),
                "SUCCESS", Map.of("keyId", key.getId(), "name", key.getName(), "quota", quota));
        Map<String, Object> result = view(key);
        result.put("apiKey", rawKey);
        return result;
    }

    @Transactional
    public Map<String, Object> update(Long agentId, Long keyId, Map<String, Object> req, JwtPrincipal principal) {
        AgentRuntimeKey key = key(agentId, keyId);
        if (req.containsKey("name")) {
            String name = str(req.get("name"));
            if (name == null || name.isBlank()) throw new BusinessException(400, "Key 名称不能为空");
            key.setName(name.trim());
        }
        if (req.containsKey("dailyQuota")) key.setDailyQuota(nonNegative(req.get("dailyQuota")));
        if (req.containsKey("status")) {
            String status = String.valueOf(req.get("status")).toUpperCase();
            if (!List.of("ENABLED", "DISABLED").contains(status)) throw new BusinessException(400, "状态仅支持 ENABLED/DISABLED");
            key.setStatus(status);
        }
        key.setUpdatedAt(LocalDateTime.now());
        keyMapper.updateById(key);
        auditService.record(principal.userId(), "runtime_key.update", "agent", String.valueOf(agentId),
                "SUCCESS", Map.of("keyId", keyId, "status", key.getStatus()));
        return view(key);
    }

    @Transactional
    public void delete(Long agentId, Long keyId, JwtPrincipal principal) {
        AgentRuntimeKey key = key(agentId, keyId);
        keyMapper.deleteById(key.getId());
        auditService.record(principal.userId(), "runtime_key.delete", "agent", String.valueOf(agentId),
                "SUCCESS", Map.of("keyId", keyId, "name", key.getName()));
    }

    /** 部署时注入容器：只下发哈希、配额与展示名。 */
    public List<AgentRuntimeKey> enabledKeys(Long agentId) {
        return keyMapper.selectList(new LambdaQueryWrapper<AgentRuntimeKey>()
                .eq(AgentRuntimeKey::getAgentId, agentId)
                .eq(AgentRuntimeKey::getStatus, "ENABLED")
                .orderByAsc(AgentRuntimeKey::getId));
    }

    private AgentRuntimeKey key(Long agentId, Long keyId) {
        requireAgent(agentId);
        AgentRuntimeKey key = keyMapper.selectById(keyId);
        if (key == null || !key.getAgentId().equals(agentId)) throw new BusinessException(404, "调用方 Key 不存在");
        return key;
    }

    private AgentRegistry requireAgent(Long agentId) {
        AgentRegistry agent = agentMapper.selectById(agentId);
        if (agent == null || "DELETED".equals(agent.getStatus())) throw new BusinessException(404, "Agent 不存在");
        return agent;
    }

    private Map<String, Object> view(AgentRuntimeKey key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", key.getId());
        m.put("name", key.getName());
        m.put("keyPrefix", key.getKeyPrefix());
        m.put("callerId", key.getKeyHash() == null ? null : key.getKeyHash().substring(0, 12));
        m.put("dailyQuota", key.getDailyQuota());
        m.put("status", key.getStatus());
        m.put("createdAt", key.getCreatedAt());
        return m;
    }

    private int nonNegative(Object value) {
        int quota = 0;
        if (value instanceof Number n) quota = n.intValue();
        else if (value != null && !String.valueOf(value).isBlank()) {
            try {
                quota = Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                throw new BusinessException(400, "日配额必须是整数");
            }
        }
        if (quota < 0) throw new BusinessException(400, "日配额不能为负数");
        if (quota > 1_000_000) throw new BusinessException(400, "日配额过大");
        return quota;
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BusinessException(500, "Key 摘要计算失败");
        }
    }
}
