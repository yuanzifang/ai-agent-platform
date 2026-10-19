package com.platform.capability.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.entity.AgentToolBinding;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.agent.mapper.AgentToolBindingMapper;
import com.platform.capability.entity.SkillDependency;
import com.platform.capability.entity.SkillRegistry;
import com.platform.capability.entity.ToolRegistry;
import com.platform.capability.entity.ToolVersion;
import com.platform.capability.mapper.SkillDependencyMapper;
import com.platform.capability.mapper.SkillRegistryMapper;
import com.platform.capability.mapper.ToolRegistryMapper;
import com.platform.capability.mapper.ToolVersionMapper;
import com.platform.capability.runtime.ToolRuntimeService;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.common.security.CryptoService;
import com.platform.common.security.NetworkPolicyService;
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
import java.util.UUID;

/**
 * Tool 管理（需求 3.14 / P1）：内置/API 工具 CRUD、参数 Schema、测试、超时重试、版本快照。
 * 内置工具只读；被 Agent 或技能引用时删除为软删除；鉴权密钥返回时脱敏。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolService {

    private static final String MASK = "******";

    private final ToolRegistryMapper toolMapper;
    private final ToolVersionMapper versionMapper;
    private final AgentToolBindingMapper bindingMapper;
    private final AgentRegistryMapper agentMapper;
    private final SkillDependencyMapper skillDepMapper;
    private final SkillRegistryMapper skillMapper;
    private final ToolRuntimeService runtimeService;
    private final AuditService auditService;
    private final CryptoService cryptoService;
    private final NetworkPolicyService networkPolicy;
    private final ObjectMapper objectMapper;

    public Map<String, Object> list(String keyword, String category, String type) {
        Long tenantId = TenantContext.getTenantId();
        List<Map<String, Object>> list = new ArrayList<>();
        for (ToolRegistry t : toolMapper.selectVisible(tenantId)) {
            if (keyword != null && !keyword.isBlank()
                    && !contains(t.getName(), keyword) && !contains(t.getDescription(), keyword)) continue;
            if (category != null && !category.isBlank() && !category.equals(t.getCategory())) continue;
            if (type != null && !type.isBlank() && !type.equalsIgnoreCase(t.getToolType())) continue;
            list.add(view(t));
        }
        return Map.of("list", list, "total", list.size());
    }

    public Map<String, Object> detail(Long id) {
        ToolRegistry t = visible(id);
        Map<String, Object> v = view(t);
        v.put("referencedAgents", referencedAgents(id));
        v.put("referencedSkills", referencedSkills(id));
        return v;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> req, JwtPrincipal principal) {
        Long tenantId = TenantContext.getTenantId();
        String name = str(req.get("name"));
        if (name == null || name.isBlank()) throw new BusinessException(400, "工具名称不能为空");
        ensureNameUnique(tenantId, name, null);
        String toolType = str(req.getOrDefault("toolType", "API")).toUpperCase(Locale.ROOT);
        if (!"API".equals(toolType)) {
            throw new BusinessException(400, "自定义工具仅支持 API 类型（MCP 工具由 MCP Server 管理）");
        }
        String apiConfig = normalizeApiConfig(req.get("apiConfig"), null);
        ToolRegistry t = new ToolRegistry();
        t.setToolKey("tool_" + randomKey());
        t.setName(name.trim());
        t.setDescription(str(req.get("description")));
        t.setIconUrl(str(req.get("iconUrl")));
        t.setCategory(str(req.get("category")));
        t.setTags(json(req.get("tags")));
        t.setToolType(toolType);
        t.setApiConfig(apiConfig);
        t.setTimeoutMs(clamp(req.get("timeoutMs"), 500, 120000, 10000));
        t.setRetryTimes(clamp(req.get("retryTimes"), 0, 3, 2));
        t.setStatus("ENABLED");
        t.setIsBuiltin(0);
        t.setTenantId(tenantId);
        t.setCreatedBy(principal.userId());
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        toolMapper.insert(t);
        auditService.record(principal.userId(), "tool.create", "tool", String.valueOf(t.getId()),
                "SUCCESS", Map.of("name", t.getName()));
        return Map.of("id", t.getId());
    }

    @Transactional
    public void update(Long id, Map<String, Object> req, JwtPrincipal principal) {
        ToolRegistry t = visible(id);
        requireCustom(t);
        if (req.get("name") != null) {
            String name = str(req.get("name"));
            if (name == null || name.isBlank()) throw new BusinessException(400, "工具名称不能为空");
            ensureNameUnique(t.getTenantId(), name, id);
            t.setName(name.trim());
        }
        if (req.containsKey("description")) t.setDescription(str(req.get("description")));
        if (req.containsKey("iconUrl")) t.setIconUrl(str(req.get("iconUrl")));
        if (req.containsKey("category")) t.setCategory(str(req.get("category")));
        if (req.containsKey("tags")) t.setTags(json(req.get("tags")));
        if (req.containsKey("apiConfig")) {
            if ("MCP".equals(t.getToolType())) {
                throw new BusinessException(400, "MCP 工具配置由 MCP Server 同步，不可手工修改");
            }
            t.setApiConfig(normalizeApiConfig(req.get("apiConfig"), t.getApiConfig()));
        }
        if (req.containsKey("timeoutMs")) t.setTimeoutMs(clamp(req.get("timeoutMs"), 500, 120000, 10000));
        if (req.containsKey("retryTimes")) t.setRetryTimes(clamp(req.get("retryTimes"), 0, 3, 2));
        if (req.containsKey("status")) {
            String status = str(req.get("status"));
            if (!List.of("ENABLED", "DISABLED").contains(status)) throw new BusinessException(400, "非法状态");
            t.setStatus(status);
        }
        t.setUpdatedAt(LocalDateTime.now());
        toolMapper.updateById(t);
        auditService.record(principal.userId(), "tool.update", "tool", String.valueOf(id), "SUCCESS",
                Map.of("name", t.getName()));
    }

    @Transactional
    public Map<String, Object> delete(Long id, JwtPrincipal principal) {
        ToolRegistry t = visible(id);
        requireCustom(t);
        long agentRefs = bindingMapper.selectCount(new LambdaQueryWrapper<AgentToolBinding>()
                .eq(AgentToolBinding::getToolId, id));
        long skillRefs = skillDepMapper.selectByTool(id).size();
        boolean soft = agentRefs + skillRefs > 0;
        if (soft) {
            t.setStatus("DELETED");
            t.setUpdatedAt(LocalDateTime.now());
            toolMapper.updateById(t);
        } else {
            versionMapper.delete(new LambdaQueryWrapper<ToolVersion>().eq(ToolVersion::getToolId, id));
            toolMapper.deleteById(id);
        }
        auditService.record(principal.userId(), "tool.delete", "tool", String.valueOf(id), "SUCCESS",
                Map.of("name", t.getName(), "soft", soft));
        return Map.of("softDeleted", soft, "referencedAgents", agentRefs, "referencedSkills", skillRefs);
    }

    public Map<String, Object> references(Long id) {
        ToolRegistry t = visible(id);
        return Map.of("agents", referencedAgents(id), "skills", referencedSkills(id), "tool", t.getName());
    }

    /** 工具测试（需求 3.14(2)）：直接执行一次，展示结果/耗时。 */
    public Map<String, Object> test(Long id, String argsJson) {
        ToolRegistry t = visible(id);
        try {
            return runtimeService.testTool(t, argsJson);
        } catch (Exception e) {
            throw new BusinessException(400, "测试失败：" + rootMessage(e));
        }
    }

    @Transactional
    public Map<String, Object> publish(Long id, JwtPrincipal principal) {
        ToolRegistry t = visible(id);
        long count = versionMapper.selectCount(new LambdaQueryWrapper<ToolVersion>()
                .eq(ToolVersion::getToolId, id));
        String version = "V" + (count + 1);
        ToolVersion v = new ToolVersion();
        v.setToolId(id);
        v.setVersion(version);
        v.setSnapshot(json(snapshot(t)));
        v.setCreatedBy(principal.userId());
        v.setTenantId(t.getTenantId());
        v.setCreatedAt(LocalDateTime.now());
        versionMapper.insert(v);
        auditService.record(principal.userId(), "tool.publish", "tool", String.valueOf(id),
                "SUCCESS", Map.of("version", version));
        return Map.of("version", version);
    }

    public List<ToolVersion> versions(Long id) {
        visible(id);
        return versionMapper.selectList(new LambdaQueryWrapper<ToolVersion>()
                .eq(ToolVersion::getToolId, id).orderByDesc(ToolVersion::getId));
    }

    @Transactional
    public Map<String, Object> rollback(Long id, String version, JwtPrincipal principal) {
        ToolRegistry t = visible(id);
        requireCustom(t);
        ToolVersion target = versionMapper.selectList(new LambdaQueryWrapper<ToolVersion>()
                        .eq(ToolVersion::getToolId, id).eq(ToolVersion::getVersion, version))
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException(404, "版本不存在，请先发布"));
        Map<String, Object> snap = fromJsonMap(target.getSnapshot());
        t.setName(str(snap.get("name")));
        t.setDescription(str(snap.get("description")));
        t.setCategory(str(snap.get("category")));
        t.setTags(json(snap.get("tags")));
        t.setToolType(str(snap.getOrDefault("toolType", "API")));
        if (snap.get("apiConfig") != null) t.setApiConfig(json(snap.get("apiConfig")));
        if (snap.get("timeoutMs") instanceof Number n) t.setTimeoutMs(n.intValue());
        if (snap.get("retryTimes") instanceof Number n) t.setRetryTimes(n.intValue());
        t.setUpdatedAt(LocalDateTime.now());
        toolMapper.updateById(t);
        return publish(id, principal);
    }

    // ---------- helpers ----------

    private ToolRegistry visible(Long id) {
        ToolRegistry t = toolMapper.findVisible(id, TenantContext.getTenantId());
        if (t == null) throw new BusinessException(404, "工具不存在");
        return t;
    }

    private void requireCustom(ToolRegistry t) {
        if (t.getIsBuiltin() != null && t.getIsBuiltin() == 1) {
            throw new BusinessException(403, "内置工具不可修改");
        }
    }

    private void ensureNameUnique(Long tenantId, String name, Long excludeId) {
        for (ToolRegistry t : toolMapper.selectVisible(tenantId)) {
            if (!t.getId().equals(excludeId) && name.trim().equalsIgnoreCase(t.getName())) {
                throw new BusinessException(400, "工具名称已存在：" + name.trim());
            }
        }
    }

    private Map<String, Object> view(ToolRegistry t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("toolKey", t.getToolKey());
        m.put("name", t.getName());
        m.put("description", t.getDescription());
        m.put("iconUrl", t.getIconUrl());
        m.put("category", t.getCategory());
        m.put("tags", fromJsonValue(t.getTags()));
        m.put("toolType", t.getToolType());
        m.put("mcpServerId", t.getMcpServerId());
        m.put("apiConfig", maskedConfig(t.getApiConfig()));
        m.put("timeoutMs", t.getTimeoutMs());
        m.put("retryTimes", t.getRetryTimes());
        m.put("status", t.getStatus());
        m.put("isBuiltin", t.getIsBuiltin() != null && t.getIsBuiltin() == 1);
        m.put("createdAt", t.getCreatedAt());
        m.put("updatedAt", t.getUpdatedAt());
        return m;
    }

    /** 鉴权密钥脱敏（不返回明文；更新时传 ****** 视为保持不变）。 */
    private Map<String, Object> maskedConfig(String raw) {
        Map<String, Object> cfg = fromJsonMap(raw);
        if (cfg.get("auth") instanceof Map<?, ?> authRaw) {
            Map<String, Object> auth = new LinkedHashMap<>();
            authRaw.forEach((k, v) -> auth.put(String.valueOf(k), v));
            for (String key : List.of("value", "token", "password")) {
                Object v = auth.get(key);
                if (v != null && !String.valueOf(v).isBlank()) auth.put(key, MASK);
            }
            cfg = new LinkedHashMap<>(cfg);
            cfg.put("auth", auth);
        }
        return cfg;
    }

    @SuppressWarnings("unchecked")
    private String normalizeApiConfig(Object incoming, String existingRaw) {
        Map<String, Object> cfg;
        try {
            cfg = incoming instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m)
                    : incoming instanceof String s && !s.isBlank()
                    ? objectMapper.readValue(s, Map.class) : new LinkedHashMap<>();
        } catch (Exception e) {
            throw new BusinessException(400, "apiConfig 不是合法 JSON");
        }
        String url = str(cfg.get("url"));
        networkPolicy.validateHttpUrl(url, "API 地址");
        String method = str(cfg.getOrDefault("method", "GET")).toUpperCase(Locale.ROOT);
        if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
            throw new BusinessException(400, "不支持的请求方法：" + method);
        }
        cfg.put("method", method);
        if (cfg.get("headers") != null && !(cfg.get("headers") instanceof Map)) {
            throw new BusinessException(400, "请求头需为 JSON 对象");
        }
        if (cfg.get("params") instanceof List<?> params) {
            java.util.Set<String> names = new java.util.HashSet<>();
            for (Object o : params) {
                if (!(o instanceof Map<?, ?> p)) throw new BusinessException(400, "参数定义格式错误");
                String name = str(p.get("name"));
                if (name == null || !name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                    throw new BusinessException(400, "参数名非法：" + name);
                }
                if (!names.add(name)) throw new BusinessException(400, "参数名重复：" + name);
                String in = p.get("in") == null ? "query" : String.valueOf(p.get("in"));
                if (!List.of("query", "body", "path").contains(in)) {
                    throw new BusinessException(400, "参数位置仅支持 query/body/path：" + name);
                }
            }
        }
        // 鉴权密钥合并：脱敏占位或空值沿用原配置
        Map<String, Object> existingAuth = new LinkedHashMap<>();
        if (existingRaw != null) {
            Object oldAuth = fromJsonMap(existingRaw).get("auth");
            if (oldAuth instanceof Map<?, ?> m) {
                m.forEach((k, v) -> existingAuth.put(String.valueOf(k), v));
            }
        }
        Map<String, Object> auth = new LinkedHashMap<>();
        if (cfg.get("auth") instanceof Map<?, ?> am) {
            am.forEach((k, v) -> auth.put(String.valueOf(k), v));
        }
        String type = str(auth.getOrDefault("type", "NONE")).toUpperCase(Locale.ROOT);
        if (!List.of("NONE", "API_KEY", "BEARER", "BASIC").contains(type)) {
            throw new BusinessException(400, "鉴权方式不支持：" + type);
        }
        auth.put("type", type);
        for (String key : List.of("value", "token", "password")) {
            Object v = auth.get(key);
            if (v == null || String.valueOf(v).isBlank() || MASK.equals(String.valueOf(v))) {
                if (existingAuth.get(key) != null) auth.put(key, existingAuth.get(key));
            }
        }
        for (String key : List.of("value", "token", "password")) {
            Object v = auth.get(key);
            if (v != null && !String.valueOf(v).isBlank() && !String.valueOf(v).startsWith("v1:")) {
                auth.put(key, cryptoService.encrypt(String.valueOf(v)));
            }
        }
        cfg.put("auth", auth);
        try {
            return objectMapper.writeValueAsString(cfg);
        } catch (Exception e) {
            throw new BusinessException(400, "apiConfig 序列化失败");
        }
    }

    private List<String> referencedAgents(Long toolId) {
        List<Long> agentIds = bindingMapper.selectList(new LambdaQueryWrapper<AgentToolBinding>()
                        .eq(AgentToolBinding::getToolId, toolId))
                .stream().map(AgentToolBinding::getAgentId).distinct().toList();
        if (agentIds.isEmpty()) return List.of();
        return agentMapper.selectBatchIds(agentIds).stream()
                .filter(a -> !"DELETED".equals(a.getStatus()))
                .map(AgentRegistry::getName).distinct().sorted().toList();
    }

    private List<String> referencedSkills(Long toolId) {
        List<Long> skillIds = skillDepMapper.selectByTool(toolId).stream()
                .map(SkillDependency::getSkillId).distinct().toList();
        if (skillIds.isEmpty()) return List.of();
        Map<Long, String> names = new LinkedHashMap<>();
        for (SkillRegistry s : skillMapper.selectVisible(TenantContext.getTenantId())) {
            names.put(s.getId(), s.getName());
        }
        return skillIds.stream().map(names::get).filter(java.util.Objects::nonNull).distinct().sorted().toList();
    }

    private Map<String, Object> snapshot(ToolRegistry t) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("name", t.getName());
        snap.put("description", t.getDescription());
        snap.put("category", t.getCategory());
        snap.put("tags", fromJsonValue(t.getTags()));
        snap.put("toolType", t.getToolType());
        snap.put("apiConfig", fromJsonValue(t.getApiConfig()));
        snap.put("timeoutMs", t.getTimeoutMs());
        snap.put("retryTimes", t.getRetryTimes());
        return snap;
    }

    private boolean contains(String text, String keyword) {
        return text != null && text.toLowerCase().contains(keyword.toLowerCase());
    }

    private String randomKey() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private int clamp(Object value, int min, int max, int def) {
        int v = def;
        if (value instanceof Number n) v = n.intValue();
        else if (value instanceof String s && !s.isBlank()) {
            try {
                v = Integer.parseInt(s);
            } catch (NumberFormatException ignored) { }
        }
        return Math.min(Math.max(v, min), max);
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
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
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
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
}
