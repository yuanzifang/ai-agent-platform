package com.platform.capability.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.service.AgentService;
import com.platform.capability.entity.AgentTemplate;
import com.platform.capability.entity.SkillRegistry;
import com.platform.capability.entity.ToolRegistry;
import com.platform.capability.mapper.AgentTemplateMapper;
import com.platform.capability.mapper.SkillRegistryMapper;
import com.platform.capability.mapper.ToolRegistryMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板库（需求 3.24 / P1）：预置 Agent 模板列表/预览 + 一键创建（复制提示词、能力、工具/Skill 绑定）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTemplateService {

    private final AgentTemplateMapper templateMapper;
    private final ToolRegistryMapper toolMapper;
    private final SkillRegistryMapper skillMapper;
    private final AgentService agentService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public Map<String, Object> list(String keyword, String category) {
        Long tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentTemplate t : templateMapper.selectVisible(tenantId)) {
            if (keyword != null && !keyword.isBlank()
                    && !contains(t.getName(), keyword) && !contains(t.getDescription(), keyword)) continue;
            if (category != null && !category.isBlank() && !category.equals(t.getCategory())) continue;
            rows.add(view(t));
        }
        return Map.of("list", rows, "total", rows.size());
    }

    public Map<String, Object> detail(Long id) {
        return view(visible(id));
    }

    /** 从模板创建 Agent：归属当前用户，工具/Skill 按 key 解析为当前租户可见项。 */
    @Transactional
    public Map<String, Object> use(Long id, JwtPrincipal principal) {
        AgentTemplate t = visible(id);
        Long tenantId = TenantContext.getTenantId();

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("name", t.getName());
        req.put("description", t.getDescription());
        req.put("category", t.getCategory());
        req.put("systemPrompt", t.getSystemPrompt());
        req.put("openingStatement", t.getOpeningStatement());
        req.put("modelConfig", fromJsonMap(t.getModelConfig()));
        req.put("agentCapabilities", fromJsonMap(t.getAgentCapabilities()));
        req.put("memoryConfig", fromJsonMap(t.getMemoryConfig()));
        req.put("variables", fromJsonValue(t.getVariables()));
        req.put("visibility", "TENANT");
        req.put("knowledgeBases", fromJsonValue(t.getKnowledgeBases()));

        List<Map<String, Object>> tools = new ArrayList<>();
        Map<String, Long> toolIds = new LinkedHashMap<>();
        for (ToolRegistry tool : toolMapper.selectVisible(tenantId)) toolIds.put(tool.getToolKey(), tool.getId());
        for (Object o : asList(fromJsonValue(t.getTools()))) {
            String key = str(asMap(o).get("toolKey"));
            if (key != null && toolIds.containsKey(key)) {
                tools.add(Map.of("toolId", toolIds.get(key), "enabled", true));
            }
        }
        List<Map<String, Object>> skills = new ArrayList<>();
        Map<String, Long> skillIds = new LinkedHashMap<>();
        for (SkillRegistry skill : skillMapper.selectVisible(tenantId)) skillIds.put(skill.getSkillKey(), skill.getId());
        for (Object o : asList(fromJsonValue(t.getSkills()))) {
            String key = str(asMap(o).get("skillKey"));
            if (key != null && skillIds.containsKey(key)) {
                skills.add(Map.of("skillId", skillIds.get(key), "enabled", true));
            }
        }
        req.put("tools", tools);
        req.put("skills", skills);
        req.put("subAgents", List.of());
        Map<String, Object> result = agentService.create(req, principal);
        auditService.record(principal.userId(), "agent.create_from_template", "agent",
                String.valueOf(result.get("id")), "SUCCESS",
                Map.of("templateId", t.getId(), "templateName", t.getName()));
        return result;
    }

    // ---------- helpers ----------

    private AgentTemplate visible(Long id) {
        AgentTemplate t = templateMapper.findVisible(id, TenantContext.getTenantId());
        if (t == null) throw new BusinessException(404, "模板不存在");
        return t;
    }

    private Map<String, Object> view(AgentTemplate t) {
        Long tenantId = TenantContext.getTenantId();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("description", t.getDescription());
        m.put("category", t.getCategory());
        m.put("iconUrl", t.getIconUrl());
        m.put("systemPrompt", t.getSystemPrompt());
        m.put("openingStatement", t.getOpeningStatement());
        m.put("modelConfig", fromJsonMap(t.getModelConfig()));
        m.put("agentCapabilities", fromJsonMap(t.getAgentCapabilities()));
        m.put("memoryConfig", fromJsonMap(t.getMemoryConfig()));
        m.put("variables", fromJsonValue(t.getVariables()));
        m.put("isBuiltin", t.getIsBuiltin() != null && t.getIsBuiltin() == 1);
        m.put("tools", resolvedTools(t, tenantId));
        m.put("skills", resolvedSkills(t, tenantId));
        m.put("knowledgeBases", fromJsonValue(t.getKnowledgeBases()));
        return m;
    }

    private List<Map<String, Object>> resolvedTools(AgentTemplate t, Long tenantId) {
        Map<String, ToolRegistry> byKey = new LinkedHashMap<>();
        for (ToolRegistry tool : toolMapper.selectVisible(tenantId)) byKey.put(tool.getToolKey(), tool);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object o : asList(fromJsonValue(t.getTools()))) {
            String key = str(asMap(o).get("toolKey"));
            ToolRegistry tool = byKey.get(key);
            if (tool != null) result.add(Map.of("toolKey", key, "name", tool.getName(),
                    "toolType", tool.getToolType() == null ? "API" : tool.getToolType()));
        }
        return result;
    }

    private List<Map<String, Object>> resolvedSkills(AgentTemplate t, Long tenantId) {
        Map<String, SkillRegistry> byKey = new LinkedHashMap<>();
        for (SkillRegistry skill : skillMapper.selectVisible(tenantId)) byKey.put(skill.getSkillKey(), skill);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object o : asList(fromJsonValue(t.getSkills()))) {
            String key = str(asMap(o).get("skillKey"));
            SkillRegistry skill = byKey.get(key);
            if (skill != null) result.add(Map.of("skillKey", key, "name", skill.getName()));
        }
        return result;
    }

    private List<?> asList(Object o) {
        return o instanceof List<?> list ? list : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
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

    private Object fromJsonValue(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private boolean contains(String text, String keyword) {
        return text != null && text.toLowerCase().contains(keyword.toLowerCase());
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
