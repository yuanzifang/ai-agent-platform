package com.platform.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.agent.entity.AgentCredential;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.mapper.AgentCredentialMapper;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.capability.entity.ToolRegistry;
import com.platform.capability.mapper.ToolRegistryMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.common.security.CryptoService;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent 凭证管理（需求 3.2(6) / P1）：加密存储、界面不显示明文、可绑定工具；运行时由 ToolRuntimeService 解密注入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentCredentialService {

    private final AgentCredentialMapper credentialMapper;
    private final AgentRegistryMapper agentMapper;
    private final ToolRegistryMapper toolMapper;
    private final CryptoService cryptoService;
    private final AuditService auditService;

    public Map<String, Object> list(Long agentId) {
        agent(agentId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentCredential c : credentialMapper.selectList(new LambdaQueryWrapper<AgentCredential>()
                .eq(AgentCredential::getAgentId, agentId)
                .orderByDesc(AgentCredential::getId))) {
            rows.add(view(c));
        }
        return Map.of("list", rows, "total", rows.size());
    }

    @Transactional
    public Map<String, Object> create(Long agentId, Map<String, Object> req, JwtPrincipal principal) {
        AgentRegistry agent = agent(agentId);
        long count = credentialMapper.selectCount(new LambdaQueryWrapper<AgentCredential>()
                .eq(AgentCredential::getAgentId, agentId));
        if (count >= 20) throw new BusinessException(400, "每个 Agent 最多 20 个凭证");
        String name = str(req.get("name"));
        String content = str(req.get("content"));
        if (name == null || name.isBlank()) throw new BusinessException(400, "凭证名称不能为空");
        if (content == null || content.isBlank()) throw new BusinessException(400, "凭证内容不能为空");
        String type = normalizeType(req.get("type"));
        Long toolId = toolId(req.get("toolId"), agent.getTenantId());

        AgentCredential c = new AgentCredential();
        c.setAgentId(agentId);
        c.setName(name.trim());
        c.setType(type);
        c.setEncryptedContent(cryptoService.encrypt(content));
        c.setToolId(toolId);
        c.setStatus("ENABLED");
        c.setTenantId(principal.tenantId());
        c.setCreatedAt(LocalDateTime.now());
        credentialMapper.insert(c);
        auditService.record(principal.userId(), "agent.credential.create", "agent",
                String.valueOf(agentId), "SUCCESS", Map.of("credentialId", c.getId(), "name", c.getName()));
        return view(c);
    }

    @Transactional
    public void update(Long agentId, Long id, Map<String, Object> req, JwtPrincipal principal) {
        AgentCredential c = credential(agentId, id);
        if (req.containsKey("name")) {
            String name = str(req.get("name"));
            if (name == null || name.isBlank()) throw new BusinessException(400, "凭证名称不能为空");
            c.setName(name.trim());
        }
        if (req.containsKey("type")) c.setType(normalizeType(req.get("type")));
        if (req.containsKey("content")) {
            String content = str(req.get("content"));
            if (content != null && !content.isBlank()) c.setEncryptedContent(cryptoService.encrypt(content));
        }
        if (req.containsKey("toolId")) c.setToolId(toolId(req.get("toolId"), c.getTenantId()));
        if (req.containsKey("status")) {
            String status = str(req.get("status")).toUpperCase(Locale.ROOT);
            if (!List.of("ENABLED", "DISABLED").contains(status)) throw new BusinessException(400, "非法状态");
            c.setStatus(status);
        }
        credentialMapper.updateById(c);
        auditService.record(principal.userId(), "agent.credential.update", "agent",
                String.valueOf(agentId), "SUCCESS", Map.of("credentialId", id));
    }

    @Transactional
    public void delete(Long agentId, Long id, JwtPrincipal principal) {
        credential(agentId, id);
        credentialMapper.deleteById(id);
        auditService.record(principal.userId(), "agent.credential.delete", "agent",
                String.valueOf(agentId), "SUCCESS", Map.of("credentialId", id));
    }

    /** 运行时取凭证明文（绑定到指定工具；解密失败记录日志并抛出，工具调用报错）。 */
    public String resolveContent(Long agentId, Long toolId) {
        if (agentId == null || toolId == null) return null;
        AgentCredential c = credentialMapper.selectOne(new LambdaQueryWrapper<AgentCredential>()
                .eq(AgentCredential::getAgentId, agentId)
                .eq(AgentCredential::getToolId, toolId)
                .eq(AgentCredential::getStatus, "ENABLED")
                .orderByDesc(AgentCredential::getId)
                .last("LIMIT 1"));
        if (c == null) return null;
        try {
            return cryptoService.decrypt(c.getEncryptedContent());
        } catch (Exception e) {
            log.warn("Agent 凭证解密失败 agent={} credential={}：{}", agentId, c.getId(), e.getMessage());
            throw new IllegalStateException("凭证解密失败（" + c.getName() + "）");
        }
    }

    public String typeOf(Long agentId, Long toolId) {
        if (agentId == null || toolId == null) return null;
        AgentCredential c = credentialMapper.selectOne(new LambdaQueryWrapper<AgentCredential>()
                .eq(AgentCredential::getAgentId, agentId)
                .eq(AgentCredential::getToolId, toolId)
                .eq(AgentCredential::getStatus, "ENABLED")
                .orderByDesc(AgentCredential::getId)
                .last("LIMIT 1"));
        return c == null ? null : c.getType();
    }

    // ---------- helpers ----------

    private AgentRegistry agent(Long agentId) {
        AgentRegistry agent = agentMapper.selectById(agentId);
        if (agent == null || "DELETED".equals(agent.getStatus())) throw new BusinessException(404, "Agent 不存在");
        return agent;
    }

    private AgentCredential credential(Long agentId, Long id) {
        AgentCredential c = credentialMapper.selectById(id);
        if (c == null || !c.getAgentId().equals(agentId)) throw new BusinessException(404, "凭证不存在");
        return c;
    }

    private String normalizeType(Object raw) {
        String type = raw == null ? "API_KEY" : String.valueOf(raw).toUpperCase(Locale.ROOT);
        if (!List.of("API_KEY", "OAUTH", "BASIC").contains(type)) {
            throw new BusinessException(400, "凭证类型仅支持 API_KEY/OAUTH/BASIC");
        }
        return type;
    }

    private Long toolId(Object raw, Long tenantId) {
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        Long id = Long.valueOf(String.valueOf(raw));
        ToolRegistry tool = toolMapper.findVisible(id, tenantId);
        if (tool == null) throw new BusinessException(400, "绑定的工具不存在或已删除");
        return id;
    }

    private Map<String, Object> view(AgentCredential c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("agentId", c.getAgentId());
        m.put("name", c.getName());
        m.put("type", c.getType());
        m.put("contentSet", c.getEncryptedContent() != null && !c.getEncryptedContent().isBlank());
        m.put("toolId", c.getToolId());
        m.put("status", c.getStatus());
        m.put("createdAt", c.getCreatedAt());
        return m;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
