package com.platform.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.entity.AgentVersion;
import com.platform.agent.entity.AgentKnowledgeBase;
import com.platform.agent.entity.AgentSkillBinding;
import com.platform.agent.entity.AgentToolBinding;
import com.platform.agent.entity.AgentSubAgent;
import com.platform.system.entity.AgentUserGrant;
import com.platform.agent.mapper.AgentKnowledgeBaseMapper;
import com.platform.agent.mapper.AgentSkillBindingMapper;
import com.platform.agent.mapper.AgentToolBindingMapper;
import com.platform.agent.mapper.AgentSubAgentMapper;
import com.platform.billing.service.QuotaService;
import com.platform.capability.mapper.SkillRegistryMapper;
import com.platform.capability.mapper.ToolRegistryMapper;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Agent 生命周期（需求文档 3.1/3.2）：草稿编辑（If-Match 乐观锁）、发布生成版本、回滚生成新版本。
 */
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentRegistryMapper agentMapper;
    private final AgentKnowledgeBaseMapper agentKbMapper;
    private final AgentSkillBindingMapper agentSkillMapper;
    private final AgentToolBindingMapper agentToolMapper;
    private final AgentSubAgentMapper agentSubAgentMapper;
    private final SkillRegistryMapper skillRegistryMapper;
    private final ToolRegistryMapper toolRegistryMapper;
    private final AgentUserGrantMapper grantMapper;
    private final com.platform.agent.mapper.AgentToolBindingMapper agentToolBindingMapper;
    private final com.platform.agent.mapper.AgentSkillBindingMapper agentSkillBindingMapper;
    private final com.platform.eval.service.EvalService evalService;
    private final com.platform.system.service.AuditService auditService;
    private final com.platform.deploy.service.AgentDeploymentService deploymentService;
    private final AgentVersionMapper versionMapper;
    private final QuotaService quotaService;
    private final ObjectMapper objectMapper;
    private final com.platform.model.mapper.ModelMapper modelMapper;

    /** 列表页"配置完整度"标志：批量查绑定，避免 N+1。published 且零能力 → 前端提示角标。 */
    public Map<Long, Map<String, Object>> capabilityFlags(java.util.List<Long> agentIds) {
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        if (agentIds == null || agentIds.isEmpty()) return result;
        java.util.Set<Long> kbAgents = new java.util.HashSet<>();
        for (AgentKnowledgeBase kb : agentKbMapper.selectList(new LambdaQueryWrapper<AgentKnowledgeBase>()
                .in(AgentKnowledgeBase::getAgentId, agentIds))) {
            kbAgents.add(kb.getAgentId());
        }
        java.util.Set<Long> toolAgents = new java.util.HashSet<>();
        for (com.platform.agent.entity.AgentToolBinding b : agentToolBindingMapper.selectList(
                new LambdaQueryWrapper<com.platform.agent.entity.AgentToolBinding>()
                        .in(com.platform.agent.entity.AgentToolBinding::getAgentId, agentIds))) {
            toolAgents.add(b.getAgentId());
        }
        java.util.Set<Long> skillAgents = new java.util.HashSet<>();
        if (agentSkillBindingMapper != null) {
            for (com.platform.agent.entity.AgentSkillBinding b : agentSkillBindingMapper.selectList(
                    new LambdaQueryWrapper<com.platform.agent.entity.AgentSkillBinding>()
                            .in(com.platform.agent.entity.AgentSkillBinding::getAgentId, agentIds))) {
                skillAgents.add(b.getAgentId());
            }
        }
        for (Long id : agentIds) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hasKb", kbAgents.contains(id));
            m.put("hasTool", toolAgents.contains(id));
            m.put("hasSkill", skillAgents.contains(id));
            m.put("incomplete", !(kbAgents.contains(id) || toolAgents.contains(id) || skillAgents.contains(id)));
            result.put(id, m);
        }
        return result;
    }

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

    @Transactional
    public Map<String, Object> create(Map<String, Object> req, JwtPrincipal principal) {
        Map<String, Object> modelConfig = asMap(req.get("modelConfig"));
        if (modelConfig == null || !modelConfig.containsKey("modelName")) {
            throw new BusinessException(400, "必须选择模型（modelConfig.modelName）");
        }
        validateAgentModel(modelConfig, principal.tenantId());
        quotaService.checkAgentCreate();
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
        replaceSkills(agent.getId(), agent.getTenantId(), req.get("skills"));
        replaceTools(agent.getId(), agent.getTenantId(), req.get("tools"));
        replaceSubAgents(agent.getId(), agent.getTenantId(), req.get("subAgents"));
        return Map.of("id", agent.getId(), "agentKey", agent.getAgentKey());
    }

    /** 按面板整体覆盖（需求文档 7.3 PUT 语义）；If-Match 携带 updated_at，冲突返回 409。 */
    @Transactional
    public Map<String, Object> update(Long id, Map<String, Object> req, String ifMatch) {
        AgentRegistry current = get(id); // 存在性 + 租户过滤（插件自动注入 tenant_id）
        LocalDateTime expected = parseIfMatch(ifMatch);
        if (req.containsKey("modelConfig")) {
            validateAgentModel(asMap(req.get("modelConfig")), current.getTenantId());
        }

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
        boolean skillsTouched = req.containsKey("skills");
        boolean toolsTouched = req.containsKey("tools");
        boolean subAgentsTouched = req.containsKey("subAgents");

        LambdaUpdateWrapper<AgentRegistry> uw = new LambdaUpdateWrapper<AgentRegistry>()
                .eq(AgentRegistry::getId, id)
                .eq(expected != null, AgentRegistry::getUpdatedAt, expected)
                .set(AgentRegistry::getUpdatedAt, LocalDateTime.now());
        // MyBatis-Plus updateById 忽略 null 字段，这里用 wrapper 直接 set 非空变更
        int rows = agentMapper.update(patch, uw);
        if (rows == 0) {
            throw new BusinessException(409, "内容已被他人修改，请刷新后重试");
        }
        Long tenantId = get(id).getTenantId();
        if (kbTouched) replaceKnowledgeBases(id, tenantId, req.get("knowledgeBases"));
        if (skillsTouched) replaceSkills(id, tenantId, req.get("skills"));
        if (toolsTouched) replaceTools(id, tenantId, req.get("tools"));
        if (subAgentsTouched) replaceSubAgents(id, tenantId, req.get("subAgents"));
        return Map.of("updatedAt", agentMapper.selectById(id).getUpdatedAt()
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }

    public void delete(Long id, JwtPrincipal principal) {
        get(id);
        AgentRegistry patch = new AgentRegistry();
        patch.setId(id);
        patch.setStatus("DELETED");
        agentMapper.updateById(patch);
        try {
            deploymentService.removeIfExists(id, principal);
        } catch (Exception ignored) {
            // 清理容器失败不阻塞 Agent 删除
        }
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
        // 预发布门禁已移至 Controller 层执行（门禁含评测轮询等待，
        // 不能在 @Transactional 内睡眠占住数据库连接）
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
        if (snap.containsKey("name")) patch.setName(str(snap.get("name")));
        if (snap.containsKey("description")) patch.setDescription(str(snap.get("description")));
        if (snap.containsKey("systemPrompt")) patch.setSystemPrompt(str(snap.get("systemPrompt")));
        if (snap.containsKey("openingStatement")) patch.setOpeningStatement(str(snap.get("openingStatement")));
        if (snap.containsKey("agentCapabilities")) patch.setAgentCapabilities(toJson(snap.get("agentCapabilities")));
        if (snap.containsKey("memoryConfig")) patch.setMemoryConfig(toJson(snap.get("memoryConfig")));
        if (snap.containsKey("modelConfig")) patch.setModelConfig(toJson(snap.get("modelConfig")));
        if (snap.containsKey("retrievalConfig")) patch.setRetrievalConfig(toJson(snap.get("retrievalConfig")));
        if (snap.containsKey("variables")) patch.setVariables(toJson(snap.get("variables")));
        agentMapper.updateById(patch);

        // 关联配置也回到快照：知识库/技能/工具/子 Agent（旧版本快照没有这些键时保持现状）
        Long tenantId = agent.getTenantId();
        if (snap.containsKey("knowledgeBases")) replaceKnowledgeBases(id, tenantId, snap.get("knowledgeBases"));
        if (snap.containsKey("skills")) replaceSkills(id, tenantId, snap.get("skills"));
        if (snap.containsKey("tools")) replaceTools(id, tenantId, snap.get("tools"));
        if (snap.containsKey("subAgents")) replaceSubAgents(id, tenantId, snap.get("subAgents"));

        int count = versionMapper.countByAgent(id);
        String newVersion = "V" + (count + 1);
        AgentVersion v = new AgentVersion();
        v.setAgentId(id);
        v.setVersion(newVersion);
        v.setSnapshot(target.getSnapshot());
        v.setCreatedBy(principal.userId());
        v.setCreatedAt(LocalDateTime.now());
        v.setTenantId(tenantId);
        versionMapper.insert(v);
        auditService.record(principal.userId(), "agent.rollback", "agent", String.valueOf(id),
                "SUCCESS", Map.of("from", version, "newVersion", newVersion));
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
        v.put("visibility", a.getVisibility());
        v.put("systemPrompt", nvl(a.getSystemPrompt()));
        v.put("openingStatement", nvl(a.getOpeningStatement()));
        v.put("agentCapabilities", fromJsonMap(a.getAgentCapabilities()));
        v.put("memoryConfig", fromJsonMap(a.getMemoryConfig()));
        v.put("modelConfig", fromJsonMap(a.getModelConfig()));
        v.put("retrievalConfig", fromJsonMap(a.getRetrievalConfig()));
        v.put("variables", fromJsonList(a.getVariables()));
        v.put("updatedAt", a.getUpdatedAt() == null ? null
                : a.getUpdatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        v.put("listed", a.getListed() != null && a.getListed() == 1);
        v.put("knowledgeBases", knowledgeBases(id));
        v.put("skills", skillBindings(id));
        v.put("tools", toolBindings(id));
        v.put("subAgents", subAgentBindings(id));
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

    /** 技能绑定（含禁用行，用于编辑回显与已失效检测）。 */
    public java.util.List<Map<String, Object>> skillBindings(Long agentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentSkillBinding row : agentSkillMapper.selectList(new LambdaQueryWrapper<AgentSkillBinding>()
                .eq(AgentSkillBinding::getAgentId, agentId).orderByAsc(AgentSkillBinding::getSortOrder))) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("skillId", row.getSkillId());
            b.put("enabled", row.getEnabled() != null && row.getEnabled() == 1);
            b.put("sortOrder", row.getSortOrder());
            result.add(b);
        }
        return result;
    }

    public java.util.List<Map<String, Object>> toolBindings(Long agentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentToolBinding row : agentToolMapper.selectList(new LambdaQueryWrapper<AgentToolBinding>()
                .eq(AgentToolBinding::getAgentId, agentId).orderByAsc(AgentToolBinding::getSortOrder))) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("toolId", row.getToolId());
            b.put("enabled", row.getEnabled() != null && row.getEnabled() == 1);
            b.put("params", fromJsonMap(row.getParams()));
            b.put("sortOrder", row.getSortOrder());
            result.add(b);
        }
        return result;
    }

    public java.util.List<Map<String, Object>> subAgentBindings(Long agentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentSubAgent row : agentSubAgentMapper.selectList(new LambdaQueryWrapper<AgentSubAgent>()
                .eq(AgentSubAgent::getAgentId, agentId).orderByAsc(AgentSubAgent::getSortOrder))) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("subAgentId", row.getSubAgentId());
            b.put("enabled", row.getEnabled() != null && row.getEnabled() == 1);
            b.put("sortOrder", row.getSortOrder());
            result.add(b);
        }
        return result;
    }

    /** 整体覆盖技能绑定（先全量校验，再删除重写，避免失败时丢配置）。 */
    private void replaceSkills(Long agentId, Long tenantId, Object value) {
        List<AgentSkillBinding> rows = new ArrayList<>();
        if (value instanceof java.util.List<?> list) {
            Set<Long> seen = new HashSet<>();
            int order = 0;
            for (Object o : list) {
                Map<String, Object> b = asMap(o);
                Long skillId = toLong(b.get("skillId"));
                if (skillId == null || !seen.add(skillId)) continue;
                if (skillRegistryMapper.findVisible(skillId, tenantId) == null) {
                    throw new BusinessException(400, "技能不存在或已删除：" + skillId);
                }
                AgentSkillBinding row = new AgentSkillBinding();
                row.setAgentId(agentId);
                row.setSkillId(skillId);
                row.setEnabled(Boolean.FALSE.equals(b.get("enabled")) ? 0 : 1);
                row.setSortOrder(order++);
                row.setTenantId(tenantId);
                row.setCreatedAt(LocalDateTime.now());
                rows.add(row);
            }
        }
        agentSkillMapper.delete(new LambdaQueryWrapper<AgentSkillBinding>()
                .eq(AgentSkillBinding::getAgentId, agentId));
        rows.forEach(agentSkillMapper::insert);
    }

    private void replaceTools(Long agentId, Long tenantId, Object value) {
        List<AgentToolBinding> rows = new ArrayList<>();
        if (value instanceof java.util.List<?> list) {
            Set<Long> seen = new HashSet<>();
            int order = 0;
            for (Object o : list) {
                Map<String, Object> b = asMap(o);
                Long toolId = toLong(b.get("toolId"));
                if (toolId == null || !seen.add(toolId)) continue;
                if (toolRegistryMapper.findVisible(toolId, tenantId) == null) {
                    throw new BusinessException(400, "工具不存在或已删除：" + toolId);
                }
                AgentToolBinding row = new AgentToolBinding();
                row.setAgentId(agentId);
                row.setToolId(toolId);
                row.setEnabled(Boolean.FALSE.equals(b.get("enabled")) ? 0 : 1);
                if (b.get("params") != null) row.setParams(toJson(asMap(b.get("params"))));
                row.setSortOrder(order++);
                row.setTenantId(tenantId);
                row.setCreatedAt(LocalDateTime.now());
                rows.add(row);
            }
        }
        agentToolMapper.delete(new LambdaQueryWrapper<AgentToolBinding>()
                .eq(AgentToolBinding::getAgentId, agentId));
        rows.forEach(agentToolMapper::insert);
    }

    /** 子 Agent 绑定：上限 10、禁止自引用、保存期环检测（需求 3.1.3(8)）；先校验后落库。 */
    private void replaceSubAgents(Long agentId, Long tenantId, Object value) {
        List<AgentSubAgent> rows = new ArrayList<>();
        if (value instanceof java.util.List<?> list) {
            List<Long> ids = new ArrayList<>();
            for (Object o : list) {
                Long id = toLong(asMap(o).get("subAgentId"));
                if (id != null && !ids.contains(id)) ids.add(id);
            }
            if (ids.size() > 10) throw new BusinessException(400, "子 Agent 数量上限为 10 个");
            for (Long subId : ids) {
                if (subId.equals(agentId)) throw new BusinessException(400, "不能把自身配置为子 Agent");
                AgentRegistry sub = agentMapper.selectById(subId);
                if (sub == null || "DELETED".equals(sub.getStatus())) {
                    throw new BusinessException(400, "子 Agent 不存在或已删除：" + subId);
                }
            }
            for (Long subId : ids) {
                if (reaches(subId, agentId, new HashSet<>(), 0)) {
                    throw new BusinessException(400, "子 Agent 存在循环依赖（" + subId + " 可回到当前 Agent），已阻止保存");
                }
            }
            int order = 0;
            for (Object o : list) {
                Map<String, Object> b = asMap(o);
                Long subId = toLong(b.get("subAgentId"));
                if (subId == null) continue;
                AgentSubAgent row = new AgentSubAgent();
                row.setAgentId(agentId);
                row.setSubAgentId(subId);
                row.setEnabled(Boolean.FALSE.equals(b.get("enabled")) ? 0 : 1);
                row.setSortOrder(order++);
                row.setTenantId(tenantId);
                row.setCreatedAt(LocalDateTime.now());
                rows.add(row);
            }
        }
        agentSubAgentMapper.delete(new LambdaQueryWrapper<AgentSubAgent>()
                .eq(AgentSubAgent::getAgentId, agentId));
        rows.forEach(agentSubAgentMapper::insert);
    }

    /** 判断 from 是否可通过子 Agent 链到达 target（含禁用边，避免启用后成环）。 */
    private boolean reaches(Long from, Long target, Set<Long> visited, int depth) {
        if (from.equals(target)) return true;
        if (depth > 12 || !visited.add(from)) return false;
        for (AgentSubAgent row : agentSubAgentMapper.selectList(new LambdaQueryWrapper<AgentSubAgent>()
                .eq(AgentSubAgent::getAgentId, from))) {
            if (reaches(row.getSubAgentId(), target, visited, depth + 1)) return true;
        }
        return false;
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
        snap.put("knowledgeBases", knowledgeBases(a.getId()));
        snap.put("skills", skillBindings(a.getId()));
        snap.put("tools", toolBindings(a.getId()));
        snap.put("subAgents", subAgentBindings(a.getId()));
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
    /** 平台专用模型（PLATFORM，如本地改写模型）不能绑定到 Agent，Agent 必须使用自己的模型。 */
    private void validateAgentModel(Map<String, Object> modelConfig, Long tenantId) {
        if (modelConfig == null) return;
        for (String key : List.of("modelName", "fallbackModelName")) {
            String name = str(modelConfig.get(key));
            if (name == null || name.isBlank()) continue;
            var model = modelMapper.findForPricing(name, tenantId);
            if (model != null && "PLATFORM".equalsIgnoreCase(model.getPurpose())) {
                throw new BusinessException(400,
                        "模型「" + name + "」为平台评测专用，不能用于 Agent；请选择 Agent 自己的对话模型");
            }
        }
    }

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
