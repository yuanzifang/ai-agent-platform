package com.platform.capability.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.capability.entity.PromptTemplate;
import com.platform.capability.mapper.PromptTemplateMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 模板管理（需求 3.16 / P1）：租户内模板 CRUD、{{key}} 变量提取、被 Agent 引用提示。
 * 一键填充由前端把模板内容写入 Agent 提示词，变量由 Agent 占位变量统一替换。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptTemplateService {

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    private final PromptTemplateMapper mapper;
    private final AgentRegistryMapper agentMapper;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public Map<String, Object> list(String keyword, String category) {
        LambdaQueryWrapper<PromptTemplate> qw = new LambdaQueryWrapper<PromptTemplate>()
                .eq(category != null && !category.isBlank(), PromptTemplate::getCategory, category)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(PromptTemplate::getName, keyword)
                        .or().like(PromptTemplate::getContent, keyword))
                .orderByDesc(PromptTemplate::getUpdatedAt);
        List<Map<String, Object>> rows = mapper.selectList(qw).stream().map(this::view).toList();
        return Map.of("list", rows, "total", rows.size());
    }

    public Map<String, Object> detail(Long id) {
        return view(get(id));
    }

    public Map<String, Object> create(Map<String, Object> req, JwtPrincipal principal) {
        Long tenantId = TenantContext.getTenantId();
        String name = str(req.get("name"));
        String content = str(req.get("content"));
        if (name == null || name.isBlank()) throw new BusinessException(400, "模板名称不能为空");
        if (content == null || content.isBlank()) throw new BusinessException(400, "模板内容不能为空");
        ensureNameUnique(tenantId, name.trim(), null);
        PromptTemplate t = new PromptTemplate();
        t.setName(name.trim());
        t.setCategory(str(req.get("category")));
        t.setContent(content);
        t.setVariables(variablesJson(content));
        t.setTenantId(tenantId);
        t.setCreatedBy(principal.userId());
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        mapper.insert(t);
        auditService.record(principal.userId(), "prompt_template.create", "prompt_template",
                String.valueOf(t.getId()), "SUCCESS", Map.of("name", t.getName()));
        return Map.of("id", t.getId());
    }

    public void update(Long id, Map<String, Object> req, JwtPrincipal principal) {
        PromptTemplate t = get(id);
        if (req.containsKey("name")) {
            String name = str(req.get("name"));
            if (name == null || name.isBlank()) throw new BusinessException(400, "模板名称不能为空");
            ensureNameUnique(t.getTenantId(), name.trim(), id);
            t.setName(name.trim());
        }
        if (req.containsKey("content")) {
            String content = str(req.get("content"));
            if (content == null || content.isBlank()) throw new BusinessException(400, "模板内容不能为空");
            t.setContent(content);
            t.setVariables(variablesJson(content));
        }
        if (req.containsKey("category")) t.setCategory(str(req.get("category")));
        t.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(t);
        auditService.record(principal.userId(), "prompt_template.update", "prompt_template",
                String.valueOf(id), "SUCCESS", Map.of("name", t.getName()));
    }

    public void delete(Long id, JwtPrincipal principal) {
        PromptTemplate t = get(id);
        mapper.deleteById(id);
        auditService.record(principal.userId(), "prompt_template.delete", "prompt_template",
                String.valueOf(id), "SUCCESS", Map.of("name", t.getName()));
    }

    /** 被哪些 Agent 的提示词包含（取模板前 60 字做匹配，用于删除影响提示）。 */
    public Map<String, Object> references(Long id) {
        PromptTemplate t = get(id);
        String excerpt = t.getContent() == null ? "" : t.getContent().trim();
        excerpt = excerpt.length() > 60 ? excerpt.substring(0, 60) : excerpt;
        List<String> names = new ArrayList<>();
        if (!excerpt.isBlank()) {
            try {
                names = agentMapper.selectList(new LambdaQueryWrapper<AgentRegistry>()
                                .ne(AgentRegistry::getStatus, "DELETED")
                                .like(AgentRegistry::getSystemPrompt, excerpt)
                                .last("LIMIT 20"))
                        .stream().map(AgentRegistry::getName).distinct().sorted().toList();
            } catch (Exception e) {
                log.debug("模板引用检查失败 template={}：{}", id, e.getMessage());
            }
        }
        return Map.of("agents", names, "template", t.getName());
    }

    // ---------- helpers ----------

    private PromptTemplate get(Long id) {
        PromptTemplate t = mapper.selectById(id);
        if (t == null) throw new BusinessException(404, "模板不存在");
        return t;
    }

    private void ensureNameUnique(Long tenantId, String name, Long excludeId) {
        long count = mapper.selectCount(new LambdaQueryWrapper<PromptTemplate>()
                .eq(PromptTemplate::getTenantId, tenantId)
                .eq(PromptTemplate::getName, name)
                .ne(excludeId != null, PromptTemplate::getId, excludeId));
        if (count > 0) throw new BusinessException(400, "模板名称已存在：" + name);
    }

    private Map<String, Object> view(PromptTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("category", t.getCategory());
        m.put("content", t.getContent());
        m.put("variables", fromJson(t.getVariables()));
        m.put("createdBy", t.getCreatedBy());
        m.put("createdAt", t.getCreatedAt());
        m.put("updatedAt", t.getUpdatedAt());
        return m;
    }

    /** 从 {{key}} 提取变量（去重保序），需求 3.16(2)。 */
    private List<Map<String, Object>> extractVariables(String content) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher matcher = VAR.matcher(content == null ? "" : content);
        while (matcher.find()) keys.add(matcher.group(1));
        List<Map<String, Object>> vars = new ArrayList<>();
        for (String key : keys) vars.add(Map.of("key", key));
        return vars;
    }

    private String variablesJson(String content) {
        try {
            return objectMapper.writeValueAsString(extractVariables(content));
        } catch (Exception e) {
            return "[]";
        }
    }

    private Object fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
