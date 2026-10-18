package com.platform.capability.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.entity.AgentSkillBinding;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.agent.mapper.AgentSkillBindingMapper;
import com.platform.agent.service.ChatService;
import com.platform.capability.entity.SkillDependency;
import com.platform.capability.entity.SkillRegistry;
import com.platform.capability.entity.SkillVersion;
import com.platform.capability.mapper.SkillDependencyMapper;
import com.platform.capability.mapper.SkillRegistryMapper;
import com.platform.capability.mapper.SkillVersionMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.knowledge.service.KnowledgePermissionService;
import com.platform.model.entity.ModelEntity;
import com.platform.model.mapper.ModelMapper;
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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Skill 管理（需求 3.13 / P1）：租户内 CRUD、依赖（工具/知识库）、版本快照/发布/回滚、测试。
 * 内置 Skill 只读；被 Agent 引用时删除为软删除（Agent 中显示已失效，运行时跳过）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillService {

    private final SkillRegistryMapper skillMapper;
    private final SkillDependencyMapper depMapper;
    private final SkillVersionMapper versionMapper;
    private final AgentSkillBindingMapper bindingMapper;
    private final AgentRegistryMapper agentMapper;
    private final ModelMapper modelMapper;
    private final ChatService chatService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public Map<String, Object> list(String keyword, String category, String type) {
        Long tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> list = new ArrayList<>();
        for (SkillRegistry s : skillMapper.selectVisible(tenantId)) {
            if (keyword != null && !keyword.isBlank()
                    && !contains(s.getName(), keyword) && !contains(s.getDescription(), keyword)) continue;
            if (category != null && !category.isBlank() && !category.equals(s.getCategory())) continue;
            if (type != null && !type.isBlank() && !type.equalsIgnoreCase(s.getSkillType())) continue;
            list.add(view(s, true));
        }
        return Map.of("list", list, "total", list.size());
    }

    public Map<String, Object> detail(Long id) {
        SkillRegistry s = visible(id);
        Map<String, Object> v = view(s, true);
        v.put("dependencies", dependencies(id));
        v.put("referencedAgents", referencedAgents(id));
        return v;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> req, JwtPrincipal principal) {
        Long tenantId = TenantContext.getTenantId();
        String name = str(req.get("name"));
        if (name == null || name.isBlank()) throw new BusinessException(400, "技能名称不能为空");
        ensureNameUnique(tenantId, name, null);
        SkillRegistry s = new SkillRegistry();
        s.setSkillKey("skill_" + randomKey());
        s.setName(name.trim());
        s.setDescription(str(req.get("description")));
        s.setIconUrl(str(req.get("iconUrl")));
        s.setCategory(str(req.get("category")));
        s.setTags(json(req.get("tags")));
        s.setSkillType("CUSTOM");
        s.setPrompt(str(req.get("prompt")));
        s.setParams(json(req.getOrDefault("params", List.of())));
        s.setStatus("ENABLED");
        s.setIsBuiltin(0);
        s.setTenantId(tenantId);
        s.setCreatedBy(principal.userId());
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        skillMapper.insert(s);
        replaceDeps(s.getId(), tenantId, req);
        auditService.record(principal.userId(), "skill.create", "skill", String.valueOf(s.getId()),
                "SUCCESS", Map.of("name", s.getName()));
        return Map.of("id", s.getId());
    }

    @Transactional
    public void update(Long id, Map<String, Object> req, JwtPrincipal principal) {
        SkillRegistry s = visible(id);
        requireCustom(s);
        if (req.get("name") != null) {
            String name = str(req.get("name"));
            if (name == null || name.isBlank()) throw new BusinessException(400, "技能名称不能为空");
            ensureNameUnique(s.getTenantId(), name, id);
            s.setName(name.trim());
        }
        if (req.containsKey("description")) s.setDescription(str(req.get("description")));
        if (req.containsKey("iconUrl")) s.setIconUrl(str(req.get("iconUrl")));
        if (req.containsKey("category")) s.setCategory(str(req.get("category")));
        if (req.containsKey("tags")) s.setTags(json(req.get("tags")));
        if (req.containsKey("prompt")) s.setPrompt(str(req.get("prompt")));
        if (req.containsKey("params")) s.setParams(json(req.getOrDefault("params", List.of())));
        if (req.containsKey("status")) {
            String status = str(req.get("status"));
            if (!List.of("ENABLED", "DISABLED").contains(status)) throw new BusinessException(400, "非法状态");
            s.setStatus(status);
        }
        s.setUpdatedAt(LocalDateTime.now());
        skillMapper.updateById(s);
        if (req.containsKey("toolIds") || req.containsKey("knowledgeBaseIds")) replaceDeps(id, s.getTenantId(), req);
        auditService.record(principal.userId(), "skill.update", "skill", String.valueOf(id), "SUCCESS",
                Map.of("name", s.getName()));
    }

    /** 被 Agent 引用时软删除（保留绑定，Agent 显示已失效）；否则硬删除。 */
    @Transactional
    public Map<String, Object> delete(Long id, JwtPrincipal principal) {
        SkillRegistry s = visible(id);
        requireCustom(s);
        long refs = bindingMapper.selectCount(new LambdaQueryWrapper<AgentSkillBinding>()
                .eq(AgentSkillBinding::getSkillId, id));
        if (refs > 0) {
            s.setStatus("DELETED");
            s.setUpdatedAt(LocalDateTime.now());
            skillMapper.updateById(s);
        } else {
            depMapper.delete(new LambdaQueryWrapper<SkillDependency>().eq(SkillDependency::getSkillId, id));
            versionMapper.delete(new LambdaQueryWrapper<SkillVersion>().eq(SkillVersion::getSkillId, id));
            skillMapper.deleteById(id);
        }
        auditService.record(principal.userId(), "skill.delete", "skill", String.valueOf(id), "SUCCESS",
                Map.of("name", s.getName(), "soft", refs > 0));
        return Map.of("softDeleted", refs > 0, "referencedAgents", refs);
    }

    public Map<String, Object> references(Long id) {
        SkillRegistry s = visible(id);
        return Map.of("agents", referencedAgents(id), "skill", s.getName());
    }

    @Transactional
    public Map<String, Object> publish(Long id, JwtPrincipal principal) {
        SkillRegistry s = visible(id);
        long count = versionMapper.selectCount(new LambdaQueryWrapper<SkillVersion>()
                .eq(SkillVersion::getSkillId, id));
        String version = "V" + (count + 1);
        SkillVersion v = new SkillVersion();
        v.setSkillId(id);
        v.setVersion(version);
        v.setSnapshot(json(snapshot(s)));
        v.setCreatedBy(principal.userId());
        v.setTenantId(s.getTenantId());
        v.setCreatedAt(LocalDateTime.now());
        versionMapper.insert(v);
        auditService.record(principal.userId(), "skill.publish", "skill", String.valueOf(id),
                "SUCCESS", Map.of("version", version));
        return Map.of("version", version);
    }

    public List<SkillVersion> versions(Long id) {
        visible(id);
        return versionMapper.selectList(new LambdaQueryWrapper<SkillVersion>()
                .eq(SkillVersion::getSkillId, id).orderByDesc(SkillVersion::getId));
    }

    @Transactional
    public Map<String, Object> rollback(Long id, String version, JwtPrincipal principal) {
        SkillRegistry s = visible(id);
        requireCustom(s);
        SkillVersion target = versionMapper.selectList(new LambdaQueryWrapper<SkillVersion>()
                        .eq(SkillVersion::getSkillId, id).eq(SkillVersion::getVersion, version))
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException(404, "版本不存在，请先发布"));
        Map<String, Object> snap = fromJsonMap(target.getSnapshot());
        s.setName(str(snap.get("name")));
        s.setDescription(str(snap.get("description")));
        s.setCategory(str(snap.get("category")));
        s.setTags(json(snap.get("tags")));
        s.setPrompt(str(snap.get("prompt")));
        s.setParams(json(snap.getOrDefault("params", List.of())));
        s.setUpdatedAt(LocalDateTime.now());
        skillMapper.updateById(s);
        if (snap.get("dependencies") instanceof List<?> deps) {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("toolIds", deps.stream().map(this::asMap)
                    .filter(d -> "TOOL".equals(str(d.get("depType"))))
                    .map(d -> toLong(d.get("depId"))).filter(Objects::nonNull).toList());
            req.put("knowledgeBaseIds", deps.stream().map(this::asMap)
                    .filter(d -> "KNOWLEDGE_BASE".equals(str(d.get("depType"))))
                    .map(d -> toLong(d.get("depId"))).filter(Objects::nonNull).toList());
            replaceDeps(id, s.getTenantId(), req);
        }
        return publish(id, principal);
    }

    /** 测试：使用当前配置试跑（不写正式 Trace，需求 3.13(6)）。 */
    public Map<String, Object> test(Long id, String input, KnowledgePermissionService.Viewer viewer,
                                    JwtPrincipal principal) {
        SkillRegistry s = visible(id);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("systemPrompt", s.getPrompt() == null ? "" : s.getPrompt());
        config.put("variables", fromJsonValue(s.getParams()));
        config.put("agentCapabilities", Map.of("functionCall", true));
        config.put("tools", dependencies(id).stream()
                .filter(d -> "TOOL".equals(d.get("depType")))
                .map(d -> Map.of("toolId", d.get("depId"), "enabled", true)).toList());
        config.put("knowledgeBases", dependencies(id).stream()
                .filter(d -> "KNOWLEDGE_BASE".equals(d.get("depType")))
                .map(d -> Map.of("knowledgeBaseId", d.get("depId"), "enabled", true)).toList());
        ModelEntity model = modelMapper.findDefaultChat(TenantContext.getTenantId());
        config.put("modelConfig", Map.of("modelName", model == null ? "deepseek-flash" : model.getModelName(),
                "temperature", 0.3));
        return chatService.debugChat(config, input == null ? "你好" : input, viewer, principal);
    }

    // ---------- helpers ----------

    private SkillRegistry visible(Long id) {
        SkillRegistry s = skillMapper.findVisible(id, TenantContext.getTenantId());
        if (s == null) throw new BusinessException(404, "技能不存在");
        return s;
    }

    private void requireCustom(SkillRegistry s) {
        if (s.getIsBuiltin() != null && s.getIsBuiltin() == 1) {
            throw new BusinessException(403, "内置技能不可修改");
        }
    }

    private void ensureNameUnique(Long tenantId, String name, Long excludeId) {
        for (SkillRegistry s : skillMapper.selectVisible(tenantId)) {
            if (!s.getId().equals(excludeId) && name.trim().equalsIgnoreCase(s.getName())) {
                throw new BusinessException(400, "技能名称已存在：" + name.trim());
            }
        }
    }

    private Map<String, Object> view(SkillRegistry s, boolean withDeps) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("skillKey", s.getSkillKey());
        m.put("name", s.getName());
        m.put("description", s.getDescription());
        m.put("iconUrl", s.getIconUrl());
        m.put("category", s.getCategory());
        m.put("tags", fromJsonValue(s.getTags()));
        m.put("skillType", s.getSkillType());
        m.put("prompt", s.getPrompt());
        m.put("params", fromJsonValue(s.getParams()));
        m.put("status", s.getStatus());
        m.put("isBuiltin", s.getIsBuiltin() != null && s.getIsBuiltin() == 1);
        m.put("createdAt", s.getCreatedAt());
        m.put("updatedAt", s.getUpdatedAt());
        if (withDeps) m.put("dependencies", dependencies(s.getId()));
        return m;
    }

    public List<Map<String, Object>> dependencies(Long skillId) {
        List<Map<String, Object>> deps = new ArrayList<>();
        for (SkillDependency d : depMapper.selectBySkill(skillId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("depType", d.getDepType());
            m.put("depId", d.getDepId());
            deps.add(m);
        }
        return deps;
    }

    private void replaceDeps(Long skillId, Long tenantId, Map<String, Object> req) {
        depMapper.delete(new LambdaQueryWrapper<SkillDependency>().eq(SkillDependency::getSkillId, skillId));
        if (req.get("toolIds") instanceof List<?> toolIds) {
            for (Object o : toolIds) {
                Long toolId = toLong(o);
                if (toolId != null) insertDep(skillId, "TOOL", toolId, tenantId);
            }
        }
        if (req.get("knowledgeBaseIds") instanceof List<?> kbIds) {
            for (Object o : kbIds) {
                Long kbId = toLong(o);
                if (kbId != null) insertDep(skillId, "KNOWLEDGE_BASE", kbId, tenantId);
            }
        }
    }

    private void insertDep(Long skillId, String type, Long depId, Long tenantId) {
        SkillDependency d = new SkillDependency();
        d.setSkillId(skillId);
        d.setDepType(type);
        d.setDepId(depId);
        d.setTenantId(tenantId);
        d.setCreatedAt(LocalDateTime.now());
        depMapper.insert(d);
    }

    private List<String> referencedAgents(Long skillId) {
        List<Long> agentIds = bindingMapper.selectList(new LambdaQueryWrapper<AgentSkillBinding>()
                        .eq(AgentSkillBinding::getSkillId, skillId))
                .stream().map(AgentSkillBinding::getAgentId).distinct().toList();
        if (agentIds.isEmpty()) return List.of();
        return agentMapper.selectBatchIds(agentIds).stream()
                .filter(a -> !"DELETED".equals(a.getStatus()))
                .map(AgentRegistry::getName).distinct().sorted().toList();
    }

    private Map<String, Object> snapshot(SkillRegistry s) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("name", s.getName());
        snap.put("description", s.getDescription());
        snap.put("category", s.getCategory());
        snap.put("tags", fromJsonValue(s.getTags()));
        snap.put("prompt", s.getPrompt());
        snap.put("params", fromJsonValue(s.getParams()));
        snap.put("dependencies", dependencies(s.getId()));
        return snap;
    }

    private boolean contains(String text, String keyword) {
        return text != null && text.toLowerCase().contains(keyword.toLowerCase());
    }

    private String randomKey() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String json(Object o) {
        if (o == null) return null;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new BusinessException(400, "JSON 格式错误");
        }
    }

    private Object fromJsonValue(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private Long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) { }
        }
        return null;
    }
}
