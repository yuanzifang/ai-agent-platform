package com.platform.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.entity.AgentVersion;
import com.platform.agent.entity.AgentKnowledgeBase;
import com.platform.system.entity.AgentUserGrant;
import com.platform.agent.mapper.AgentKnowledgeBaseMapper;
import com.platform.system.mapper.AgentUserGrantMapper;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.agent.mapper.AgentVersionMapper;
import com.platform.common.exception.BusinessException;
import com.platform.security.JwtPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 生命周期（需求文档 3.1/3.2）：草稿编辑（If-Match 乐观锁）、发布生成版本、回滚生成新版本。
 */
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentRegistryMapper agentMapper;
    private final AgentKnowledgeBaseMapper agentKbMapper;
    private final AgentUserGrantMapper grantMapper;
    private final com.platform.eval.service.EvalService evalService;
    private final com.platform.system.service.AuditService auditService;
    private final AgentVersionMapper versionMapper;
    private final ObjectMapper objectMapper;

    /** 列表：PRIVATE Agent 仅所有者/被授权用户/管理员可见（需求 3.3）。 */
    public Page<AgentRegistry> list(String keyword, String status, long page, long size,
                                    JwtPrincipal principal) {
        LambdaQueryWrapper<AgentRegistry> qw = new LambdaQueryWrapper<AgentRegistry>()
                .ne(AgentRegistry::getStatus, "DELETED")
                .eq(status != null && !status.isBlank(), AgentRegistry::getStatus, status)
                .like(keyword != null && !keyword.isBlank(), AgentRegistry::getName, keyword)
                .orderByDesc(AgentRegistry::getUpdatedAt);
        Page<AgentRegistry> result = agentMapper.selectPage(new Page<>(page, size), qw);
        boolean admin = principal.roles().stream().anyMatch(r -> r.equals("ADMIN") || r.equals("OWNER"));
        if (!admin) {
            List<Long> granted = grantMapper.selectList(new LambdaQueryWrapper<AgentUserGrant>()
                            .eq(AgentUserGrant::getUserId, principal.userId()))
                    .stream().map(AgentUserGrant::getAgentId).toList();
            result.setRecords(result.getRecords().stream().filter(a ->
                    !"PRIVATE".equals(a.getVisibility())
                            || a.getOwnerId().equals(principal.userId())
                            || granted.contains(a.getId())).toList());
        }
        return result;
    }

    /** 使用/对话权限校验（需求 3.3）：TENANT 全员；PRIVATE 需 owner/授权/管理员。 */
    public void requireUsable(AgentRegistry agent, JwtPrincipal principal) {
        if (!"PRIVATE".equals(agent.getVisibility())) return;
        if (agent.getOwnerId().equals(principal.userId())) return;
        if (principal.roles().stream().anyMatch(r -> r.equals("ADMIN") || r.equals("OWNER"))) return;
        Long granted = grantMapper.selectCount(new LambdaQueryWrapper<AgentUserGrant>()
                .eq(AgentUserGrant::getAgentId, agent.getId())
                .eq(AgentUserGrant::getUserId, principal.userId()));
        if (granted == null || granted == 0) {
            throw new BusinessException(403, "无权使用该 Agent（私有，未获授权）");
        }
    }

    /** 已授权用户列表（编辑页权限面板）。 */
    public java.util.List<Long> grants(Long agentId) {
        get(agentId);
        return grantMapper.selectList(new LambdaQueryWrapper<AgentUserGrant>()
                        .eq(AgentUserGrant::getAgentId, agentId))
                .stream().map(AgentUserGrant::getUserId).toList();
    }

    /** 授权全量覆盖（PUT 语义）。 */
    public void replaceGrants(Long agentId, java.util.List<Long> userIds) {
        AgentRegistry agent = get(agentId);
        grantMapper.delete(new LambdaQueryWrapper<AgentUserGrant>()
                .eq(AgentUserGrant::getAgentId, agentId));
        if (userIds == null) return;
        for (Long uid : userIds.stream().distinct().toList()) {
            if (uid.equals(agent.getOwnerId())) continue;
            AgentUserGrant g = new AgentUserGrant();
            g.setAgentId(agentId);
            g.setUserId(uid);
            g.setTenantId(agent.getTenantId());
            g.setCreatedAt(LocalDateTime.now());
            grantMapper.insert(g);
        }
    }

    public AgentRegistry get(Long id) {
        AgentRegistry agent = agentMapper.selectById(id);
        if (agent == null || "DELETED".equals(agent.getStatus())) {
            throw new BusinessException(404, "Agent 不存在");
        }
        return agent;
    }

    public Map<String, Object> create(Map<String, Object> req, JwtPrincipal principal) {
        Map<String, Object> modelConfig = asMap(req.get("modelConfig"));
        if (modelConfig == null || !modelConfig.containsKey("modelName")) {
            throw new BusinessException(400, "必须选择模型（modelConfig.modelName）");
        }
        AgentRegistry agent = new AgentRegistry();
        agent.setAgentKey(UUID.randomUUID().toString().replace("-", ""));
        agent.setName(str(req.get("name")));
        agent.setDescription(str(req.get("description")));
        agent.setCategory(str(req.get("category")));
        setJsonField(req.get("tags"), agent::setTags);
        agent.setSystemPrompt(str(req.get("systemPrompt")));
        agent.setOpeningStatement(str(req.get("openingStatement")));
        setJsonField(req.get("agentCapabilities"), agent::setAgentCapabilities);
        setJsonField(req.get("memoryConfig"), agent::setMemoryConfig);
        agent.setModelConfig(toJson(modelConfig));
        setJsonField(req.get("retrievalConfig"), agent::setRetrievalConfig);
        setJsonField(req.get("variables"), agent::setVariables);
        agent.setOwnerId(principal.userId());
        agent.setVisibility("PRIVATE".equals(str(req.get("visibility"))) ? "PRIVATE" : "TENANT");
        agent.setStatus("DRAFT");
        agent.setTenantId(principal.tenantId());
        agent.setCreatedAt(LocalDateTime.now());
        agent.setUpdatedAt(LocalDateTime.now());
        agentMapper.insert(agent);
        replaceKnowledgeBases(agent.getId(), agent.getTenantId(), req.get("knowledgeBases"));
        return Map.of("id", agent.getId(), "agentKey", agent.getAgentKey());
    }

    /** 按面板整体覆盖（需求文档 7.3 PUT 语义）；If-Match 携带 updated_at，冲突返回 409。 */
    public Map<String, Object> update(Long id, Map<String, Object> req, String ifMatch) {
        get(id); // 存在性 + 租户过滤（插件自动注入 tenant_id）
        LocalDateTime expected = parseIfMatch(ifMatch);

        AgentRegistry patch = new AgentRegistry();
        patch.setId(id);
        if (req.containsKey("name")) patch.setName(str(req.get("name")));
        if (req.containsKey("visibility") && List.of("TENANT", "PRIVATE").contains(req.get("visibility"))) {
            patch.setVisibility(str(req.get("visibility")));
        }
        if (req.containsKey("description")) patch.setDescription(str(req.get("description")));
        if (req.containsKey("category")) patch.setCategory(str(req.get("category")));
        if (req.containsKey("tags")) setJsonField(req.get("tags"), patch::setTags);
        if (req.containsKey("systemPrompt")) patch.setSystemPrompt(str(req.get("systemPrompt")));
        if (req.containsKey("openingStatement")) patch.setOpeningStatement(str(req.get("openingStatement")));
        if (req.containsKey("agentCapabilities")) setJsonField(req.get("agentCapabilities"), patch::setAgentCapabilities);
        if (req.containsKey("memoryConfig")) setJsonField(req.get("memoryConfig"), patch::setMemoryConfig);
        if (req.containsKey("modelConfig")) patch.setModelConfig(toJson(asMap(req.get("modelConfig"))));
        if (req.containsKey("retrievalConfig")) setJsonField(req.get("retrievalConfig"), patch::setRetrievalConfig);
        if (req.containsKey("variables")) setJsonField(req.get("variables"), patch::setVariables);
        boolean kbTouched = req.containsKey("knowledgeBases");

        LambdaUpdateWrapper<AgentRegistry> uw = new LambdaUpdateWrapper<AgentRegistry>()
                .eq(AgentRegistry::getId, id)
                .eq(expected != null, AgentRegistry::getUpdatedAt, expected)
                .set(AgentRegistry::getUpdatedAt, LocalDateTime.now());
        // MyBatis-Plus updateById 忽略 null 字段，这里用 wrapper 直接 set 非空变更
        int rows = agentMapper.update(patch, uw);
        if (rows == 0) {
            throw new BusinessException(409, "内容已被他人修改，请刷新后重试");
        }
        if (kbTouched) {
            replaceKnowledgeBases(id, get(id).getTenantId(), req.get("knowledgeBases"));
        }
        return Map.of("updatedAt", agentMapper.selectById(id).getUpdatedAt()
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }

    public void delete(Long id, JwtPrincipal principal) {
        get(id);
        AgentRegistry patch = new AgentRegistry();
        patch.setId(id);
        patch.setStatus("DELETED");
        agentMapper.updateById(patch);
        auditService.record(principal.userId(), "agent.delete", "agent", String.valueOf(id), "SUCCESS", Map.of());
    }

    /** 发布：校验提示词与模型，创建版本快照，状态置 PUBLISHED（需求文档 3.2.3）。 */
    @Transactional
    public Map<String, Object> publish(Long id, JwtPrincipal principal) {
        return publish(id, principal, null, false);
    }

    /** 带 force：预发布门禁未过时仅 OWNER/ADMIN 可强制发布（需求 3.5）。 */
    @Transactional
    public Map<String, Object> publish(Long id, JwtPrincipal principal, Long gateDatasetId, boolean force) {
        AgentRegistry agent = get(id);
        if (agent.getSystemPrompt() == null || agent.getSystemPrompt().isBlank()) {
            throw new BusinessException(400, "提示词不能为空，无法发布");
        }
        // 预发布门禁（需求 3.5）：绑定了默认数据集才生效；force 需 OWNER/ADMIN
        if (!force) {
            Map<String, Object> gate = evalService.preReleaseGate(id, gateDatasetId, principal.userId());
            if (Boolean.FALSE.equals(gate.get("skipped")) && !Boolean.TRUE.equals(gate.get("passed"))) {
                throw new BusinessException(400, "预发布测评未通过，无法发布（通过率 "
                        + gate.get("passRate") + " < 阈值 " + gate.get("threshold")
                        + "；OWNER/ADMIN 可强制发布）");
            }
        }
        if (agent.getModelConfig() == null || !agent.getModelConfig().contains("modelName")) {
            throw new BusinessException(400, "未选择模型，无法发布");
        }
        int count = versionMapper.countByAgent(id);
        String version = "V" + (count + 1);

        AgentVersion v = new AgentVersion();
        v.setAgentId(id);
        v.setVersion(version);
        v.setSnapshot(snapshot(agent));
        v.setCreatedBy(principal.userId());
        v.setCreatedAt(LocalDateTime.now());
        v.setTenantId(agent.getTenantId());
        versionMapper.insert(v);

        AgentRegistry patch = new AgentRegistry();
        patch.setId(id);
        patch.setStatus("PUBLISHED");
        agentMapper.updateById(patch);
        auditService.record(principal.userId(), "agent.publish", "agent", String.valueOf(id),
                "SUCCESS", Map.of("version", version));
        return Map.of("version", version);
    }

    public java.util.List<AgentVersion> versions(Long id) {
        get(id);
        return versionMapper.selectList(new LambdaQueryWrapper<AgentVersion>()
                .eq(AgentVersion::getAgentId, id).orderByDesc(AgentVersion::getCreatedAt));
    }

    /** 回滚：以历史版本配置生成新版本号（需求文档 3.2.3）。 */
    @Transactional
    public Map<String, Object> rollback(Long id, String version, JwtPrincipal principal) {
        AgentRegistry agent = get(id);
        AgentVersion target = versionMapper.selectOne(new LambdaQueryWrapper<AgentVersion>()
                .eq(AgentVersion::getAgentId, id).eq(AgentVersion::getVersion, version));
        if (target == null) throw new BusinessException(404, "版本不存在");

        Map<String, Object> snap = asMap(fromJson(target.getSnapshot()));
        AgentRegistry patch = new AgentRegistry();
        patch.setId(id);
        patch.setSystemPrompt(str(snap.get("systemPrompt")));
        patch.setOpeningStatement(str(snap.get("openingStatement")));
        if (snap.get("modelConfig") != null) patch.setModelConfig(toJson(asMap(snap.get("modelConfig"))));
        agentMapper.updateById(patch);

        int count = versionMapper.countByAgent(id);
        String newVersion = "V" + (count + 1);
        AgentVersion v = new AgentVersion();
        v.setAgentId(id);
        v.setVersion(newVersion);
        v.setSnapshot(target.getSnapshot());
        v.setCreatedBy(principal.userId());
        v.setCreatedAt(LocalDateTime.now());
        v.setTenantId(agent.getTenantId());
        versionMapper.insert(v);
        return Map.of("version", newVersion, "rolledBackFrom", version);
    }

    // ---- helpers ----

    /** 编辑页详情视图：JSON 字段解析 + 知识库绑定（架构 4.5 AgentEdit 面板数据源）。 */
    public Map<String, Object> detail(Long id) {
        AgentRegistry a = get(id);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", a.getId());
        v.put("agentKey", a.getAgentKey());
        v.put("name", nvl(a.getName()));
        v.put("description", nvl(a.getDescription()));
        v.put("category", nvl(a.getCategory()));
        v.put("tags", fromJsonList(a.getTags()));
        v.put("status", a.getStatus());
        v.put("systemPrompt", nvl(a.getSystemPrompt()));
        v.put("openingStatement", nvl(a.getOpeningStatement()));
        v.put("agentCapabilities", fromJsonMap(a.getAgentCapabilities()));
        v.put("memoryConfig", fromJsonMap(a.getMemoryConfig()));
        v.put("modelConfig", fromJsonMap(a.getModelConfig()));
        v.put("retrievalConfig", fromJsonMap(a.getRetrievalConfig()));
        v.put("variables", fromJsonList(a.getVariables()));
        v.put("updatedAt", a.getUpdatedAt() == null ? null
                : a.getUpdatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        v.put("knowledgeBases", knowledgeBases(id));
        return v;
    }

    /** Agent 绑定的知识库（enabled=1），供对话 RAG 与编辑面板回显。 */
    public java.util.List<Map<String, Object>> knowledgeBases(Long agentId) {
        java.util.List<AgentKnowledgeBase> rows = agentKbMapper.selectList(
                new LambdaQueryWrapper<AgentKnowledgeBase>()
                        .eq(AgentKnowledgeBase::getAgentId, agentId));
        java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (AgentKnowledgeBase row : rows) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("knowledgeBaseId", row.getKnowledgeBaseId());
            b.put("enabled", row.getEnabled() != null && row.getEnabled() == 1);
            b.put("retrievalConfig", fromJsonMap(row.getRetrievalConfig()));
            result.add(b);
        }
        return result;
    }

    /** 整体覆盖知识库绑定（PUT 语义，需求文档 7.3）。 */
    private void replaceKnowledgeBases(Long agentId, Long tenantId, Object value) {
        agentKbMapper.delete(new LambdaQueryWrapper<AgentKnowledgeBase>()
                .eq(AgentKnowledgeBase::getAgentId, agentId));
        if (!(value instanceof java.util.List<?> list)) return;
        for (Object o : list) {
            Map<String, Object> b = asMap(o);
            Long kbId = toLong(b.get("knowledgeBaseId"));
            if (kbId == null) continue;
            AgentKnowledgeBase row = new AgentKnowledgeBase();
            row.setAgentId(agentId);
            row.setKnowledgeBaseId(kbId);
            row.setEnabled(Boolean.FALSE.equals(b.get("enabled")) ? 0 : 1);
            if (b.get("retrievalConfig") != null) row.setRetrievalConfig(toJson(asMap(b.get("retrievalConfig"))));
            row.setTenantId(tenantId);
            row.setCreatedAt(LocalDateTime.now());
            agentKbMapper.insert(row);
        }
    }

    private String nvl(String s) { return s == null ? "" : s; }

    private Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return asMap(objectMapper.readValue(json, Map.class)); } catch (Exception e) { return Map.of(); }
    }

    private java.util.List<Object> fromJsonList(String json) {
        if (json == null || json.isBlank()) return java.util.List.of();
        try { return objectMapper.readValue(json, java.util.List.class); } catch (Exception e) { return java.util.List.of(); }
    }

    private String snapshot(AgentRegistry a) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("name", a.getName());
        snap.put("description", a.getDescription());
        snap.put("systemPrompt", a.getSystemPrompt());
        snap.put("openingStatement", a.getOpeningStatement());
        snap.put("agentCapabilities", fromJson(a.getAgentCapabilities()));
        snap.put("memoryConfig", fromJson(a.getMemoryConfig()));
        snap.put("modelConfig", fromJson(a.getModelConfig()));
        snap.put("retrievalConfig", fromJson(a.getRetrievalConfig()));
        snap.put("variables", fromJson(a.getVariables()));
        return toJson(snap);
    }

    private LocalDateTime parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || "W/\"*\"".equals(ifMatch)) return null;
        try {
            return LocalDateTime.parse(ifMatch.replace("\"", "").trim(),
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            throw new BusinessException(400, "If-Match 格式错误，应为 updated_at 的 ISO 时间");
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private void setJsonField(Object value, java.util.function.Consumer<String> setter) {
        if (value == null) return;
        setter.accept(toJson(value));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        if (o == null) return null;
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        try {
            return objectMapper.readValue(String.valueOf(o), Map.class);
        } catch (Exception e) {
            throw new BusinessException(400, "JSON 字段格式错误");
        }
    }

    private Object fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private Long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s1 && !s1.isBlank()) {
            try { return Long.parseLong(s1); } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private String toJson(Object o) {
        try {
            return o == null ? null : objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new BusinessException(400, "JSON 序列化失败");
        }
    }
}
