package com.platform.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.entity.AgentToolBinding;
import com.platform.agent.entity.McpServer;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.agent.mapper.AgentToolBindingMapper;
import com.platform.agent.mapper.McpServerMapper;
import com.platform.capability.entity.SkillDependency;
import com.platform.capability.entity.SkillRegistry;
import com.platform.capability.entity.ToolRegistry;
import com.platform.capability.entity.ToolVersion;
import com.platform.capability.mapper.SkillDependencyMapper;
import com.platform.capability.mapper.SkillRegistryMapper;
import com.platform.capability.mapper.ToolRegistryMapper;
import com.platform.capability.mapper.ToolVersionMapper;
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
import java.util.Objects;
import java.util.UUID;

/**
 * MCP Server 管理（需求 3.15 / P1）：接入、连通性测试、工具发现/同步、引用检查、删除。
 * 鉴权信息 AES-GCM 加密存储，接口返回脱敏；同步生成的 MCP 工具进入 Tool 管理并可在 Agent 中绑定。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpServerService {

    private static final String MASK = "******";

    private final McpServerMapper serverMapper;
    private final McpClientService client;
    private final ToolRegistryMapper toolMapper;
    private final ToolVersionMapper toolVersionMapper;
    private final AgentToolBindingMapper bindingMapper;
    private final SkillDependencyMapper skillDepMapper;
    private final SkillRegistryMapper skillMapper;
    private final AgentRegistryMapper agentMapper;
    private final CryptoService cryptoService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public Map<String, Object> list() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (McpServer s : serverMapper.selectList(new LambdaQueryWrapper<McpServer>()
                .orderByDesc(McpServer::getId))) {
            rows.add(view(s));
        }
        return Map.of("list", rows, "total", rows.size());
    }

    public Map<String, Object> detail(Long id) {
        return view(visible(id));
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> req, JwtPrincipal principal) {
        Long tenantId = TenantContext.getTenantId();
        String name = str(req.get("name"));
        String endpoint = str(req.get("endpoint"));
        if (name == null || name.isBlank()) throw new BusinessException(400, "名称不能为空");
        validateEndpoint(endpoint);
        McpServer s = new McpServer();
        s.setName(name.trim());
        s.setEndpoint(endpoint.trim());
        s.setTransport(normalizeTransport(req.get("transport")));
        s.setCustomHeaders(normalizeHeaders(req.get("customHeaders")));
        String authType = normalizeAuthType(req.get("authType"));
        s.setAuthType(authType);
        s.setEncryptedAuth(encryptAuth(authType, asMap(req.get("auth")), null));
        s.setStatus("ENABLED");
        s.setHealthStatus("UNKNOWN");
        s.setTenantId(tenantId);
        s.setCreatedBy(principal.userId());
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        serverMapper.insert(s);
        auditService.record(principal.userId(), "mcp_server.create", "mcp_server", String.valueOf(s.getId()),
                "SUCCESS", Map.of("name", s.getName(), "endpoint", s.getEndpoint()));
        return Map.of("id", s.getId());
    }

    @Transactional
    public void update(Long id, Map<String, Object> req, JwtPrincipal principal) {
        McpServer s = visible(id);
        if (req.get("name") != null) {
            String name = str(req.get("name"));
            if (name == null || name.isBlank()) throw new BusinessException(400, "名称不能为空");
            s.setName(name.trim());
        }
        if (req.get("endpoint") != null) {
            String endpoint = str(req.get("endpoint"));
            validateEndpoint(endpoint);
            s.setEndpoint(endpoint.trim());
        }
        if (req.get("transport") != null) s.setTransport(normalizeTransport(req.get("transport")));
        if (req.containsKey("customHeaders")) s.setCustomHeaders(normalizeHeaders(req.get("customHeaders")));
        if (req.get("authType") != null) {
            String authType = normalizeAuthType(req.get("authType"));
            s.setAuthType(authType);
            s.setEncryptedAuth(encryptAuth(authType, asMap(req.get("auth")), s.getEncryptedAuth()));
        } else if (req.get("auth") != null) {
            s.setEncryptedAuth(encryptAuth(s.getAuthType(), asMap(req.get("auth")), s.getEncryptedAuth()));
        }
        s.setUpdatedAt(LocalDateTime.now());
        serverMapper.updateById(s);
        auditService.record(principal.userId(), "mcp_server.update", "mcp_server", String.valueOf(id),
                "SUCCESS", Map.of("name", s.getName()));
    }

    /** 连通性测试 + 工具列表拉取（需求 3.15(5)）；失败不抛错，返回 success=false。 */
    public Map<String, Object> test(Long id) {
        McpServer s = visible(id);
        long start = System.currentTimeMillis();
        try {
            List<McpClientService.McpTool> tools = client.listTools(s);
            updateHealth(s, "ONLINE");
            return Map.of("success", true, "latencyMs", System.currentTimeMillis() - start,
                    "toolCount", tools.size(),
                    "tools", tools.stream().map(t -> Map.of("name", t.name(),
                            "description", t.description() == null ? "" : t.description())).toList(),
                    "message", "连接正常，发现工具 " + tools.size() + " 个");
        } catch (Exception e) {
            updateHealth(s, "OFFLINE");
            return Map.of("success", false, "latencyMs", System.currentTimeMillis() - start,
                    "toolCount", 0, "tools", List.of(),
                    "message", "连接失败：" + rootMessage(e));
        }
    }

    /** 工具发现（不落库，供同步弹窗选择）。 */
    public Map<String, Object> discover(Long id) {
        McpServer s = visible(id);
        try {
            List<McpClientService.McpTool> tools = client.listTools(s);
            updateHealth(s, "ONLINE");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (McpClientService.McpTool t : tools) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", t.name());
                m.put("description", t.description());
                m.put("inputSchema", t.inputSchema());
                rows.add(m);
            }
            return Map.of("tools", rows, "total", rows.size());
        } catch (Exception e) {
            updateHealth(s, "OFFLINE");
            throw new BusinessException(400, "工具发现失败：" + rootMessage(e));
        }
    }

    /** 工具同步：按名称选择（空=全部），upsert 到 tool_registry（type=MCP）。 */
    @Transactional
    public Map<String, Object> sync(Long id, List<String> toolNames, JwtPrincipal principal) {
        McpServer s = visible(id);
        List<McpClientService.McpTool> discovered;
        try {
            discovered = client.listTools(s);
        } catch (Exception e) {
            updateHealth(s, "OFFLINE");
            throw new BusinessException(400, "工具同步失败：" + rootMessage(e));
        }
        List<String> selected = toolNames == null || toolNames.isEmpty()
                ? discovered.stream().map(McpClientService.McpTool::name).toList()
                : toolNames;
        Long tenantId = s.getTenantId();
        Map<String, ToolRegistry> existing = new LinkedHashMap<>();
        for (ToolRegistry t : toolMapper.selectList(new LambdaQueryWrapper<ToolRegistry>()
                .eq(ToolRegistry::getMcpServerId, id))) {
            Map<String, Object> cfg = fromJsonMap(t.getApiConfig());
            existing.put(String.valueOf(cfg.get("toolName")), t);
        }
        List<Map<String, Object>> synced = new ArrayList<>();
        for (McpClientService.McpTool t : discovered) {
            if (!selected.contains(t.name())) continue;
            ToolRegistry tool = existing.get(t.name());
            boolean isNew = tool == null;
            if (isNew) {
                tool = new ToolRegistry();
                tool.setToolKey(uniqueKey(id, t.name()));
                tool.setToolType("MCP");
                tool.setMcpServerId(id);
                tool.setIsBuiltin(0);
                tool.setTenantId(tenantId);
                tool.setCreatedBy(principal.userId());
                tool.setCreatedAt(LocalDateTime.now());
            }
            tool.setName(t.name());
            tool.setDescription(t.description() == null || t.description().isBlank() ? t.name() : t.description());
            tool.setCategory("MCP");
            tool.setStatus("ENABLED");
            tool.setTimeoutMs(30000);
            tool.setRetryTimes(2);
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("toolName", t.name());
            if (t.inputSchema() != null && !t.inputSchema().isBlank()) {
                try {
                    cfg.put("inputSchema", objectMapper.readTree(t.inputSchema()));
                } catch (Exception e) {
                    cfg.put("inputSchemaText", t.inputSchema());
                }
            }
            tool.setApiConfig(json(cfg));
            tool.setUpdatedAt(LocalDateTime.now());
            if (isNew) toolMapper.insert(tool);
            else toolMapper.updateById(tool);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", tool.getId());
            row.put("name", tool.getName());
            row.put("toolName", t.name());
            row.put("created", isNew);
            synced.add(row);
        }
        s.setLastSyncAt(LocalDateTime.now());
        s.setHealthStatus("ONLINE");
        s.setLastCheckedAt(LocalDateTime.now());
        serverMapper.updateById(s);
        auditService.record(principal.userId(), "mcp_server.sync", "mcp_server", String.valueOf(id),
                "SUCCESS", Map.of("name", s.getName(), "synced", synced.size()));
        return Map.of("synced", synced, "total", synced.size());
    }

    /** 已同步工具（本地缓存）。 */
    public Map<String, Object> tools(Long id) {
        McpServer s = visible(id);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ToolRegistry t : toolMapper.selectList(new LambdaQueryWrapper<ToolRegistry>()
                .eq(ToolRegistry::getMcpServerId, id).orderByAsc(ToolRegistry::getId))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("toolKey", t.getToolKey());
            m.put("name", t.getName());
            m.put("description", t.getDescription());
            m.put("status", t.getStatus());
            m.put("mcpServerId", t.getMcpServerId());
            rows.add(m);
        }
        return Map.of("list", rows, "total", rows.size(), "server", s.getName());
    }

    /** 删除影响面：该 Server 的工具被哪些 Agent / 技能引用。 */
    public Map<String, Object> references(Long id) {
        McpServer s = visible(id);
        return Map.of("server", s.getName(), "agents", referencedAgents(id), "skills", referencedSkills(id));
    }

    @Transactional
    public void delete(Long id, JwtPrincipal principal) {
        McpServer s = visible(id);
        List<String> agents = referencedAgents(id);
        List<String> skills = referencedSkills(id);
        if (!agents.isEmpty() || !skills.isEmpty()) {
            throw new BusinessException(409, "该 MCP Server 的工具被引用（"
                    + (agents.isEmpty() ? "" : "Agent：" + String.join("、", agents))
                    + (skills.isEmpty() ? "" : (agents.isEmpty() ? "" : "；") + "技能：" + String.join("、", skills))
                    + "），请先解绑后再删除");
        }
        List<ToolRegistry> tools = toolMapper.selectList(new LambdaQueryWrapper<ToolRegistry>()
                .eq(ToolRegistry::getMcpServerId, id));
        for (ToolRegistry t : tools) {
            toolVersionMapper.delete(new LambdaQueryWrapper<ToolVersion>()
                    .eq(ToolVersion::getToolId, t.getId()));
            toolMapper.deleteById(t.getId());
        }
        serverMapper.deleteById(id);
        auditService.record(principal.userId(), "mcp_server.delete", "mcp_server", String.valueOf(id),
                "SUCCESS", Map.of("name", s.getName(), "tools", tools.size()));
    }

    // ---------- helpers ----------

    private McpServer visible(Long id) {
        McpServer s = serverMapper.selectById(id);
        if (s == null) throw new BusinessException(404, "MCP Server 不存在");
        return s;
    }

    private void updateHealth(McpServer s, String health) {
        McpServer patch = new McpServer();
        patch.setId(s.getId());
        patch.setHealthStatus(health);
        patch.setLastCheckedAt(LocalDateTime.now());
        serverMapper.updateById(patch);
    }

    private void validateEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) throw new BusinessException(400, "Server 地址不能为空");
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            throw new BusinessException(400, "Server 地址仅支持 http/https");
        }
    }

    private String normalizeTransport(Object raw) {
        String transport = raw == null ? "HTTP" : String.valueOf(raw).toUpperCase(Locale.ROOT);
        if (!List.of("HTTP", "SSE").contains(transport)) {
            throw new BusinessException(400, "传输方式仅支持 HTTP/SSE");
        }
        return transport;
    }

    private String normalizeHeaders(Object raw) {
        if (raw == null) return null;
        Map<String, Object> headers;
        try {
            headers = raw instanceof Map<?, ?> m ? asMap(m)
                    : objectMapper.readValue(String.valueOf(raw), Map.class);
        } catch (Exception e) {
            throw new BusinessException(400, "自定义请求头需为 JSON 对象");
        }
        if (headers.isEmpty()) return null;
        for (String key : headers.keySet()) {
            if (key == null || key.isBlank() || key.equalsIgnoreCase("Authorization")
                    || key.equalsIgnoreCase("Mcp-Session-Id") || key.equalsIgnoreCase("Content-Type")) {
                throw new BusinessException(400, "请求头不合法或与系统头冲突：" + key);
            }
        }
        return json(headers);
    }

    private String normalizeAuthType(Object raw) {
        String type = raw == null ? "NONE" : String.valueOf(raw).toUpperCase(Locale.ROOT);
        if (!List.of("NONE", "API_KEY", "BEARER", "OAUTH", "BASIC").contains(type)) {
            throw new BusinessException(400, "不支持的鉴权类型：" + type);
        }
        return type;
    }

    /** 加密鉴权配置；更新时脱敏占位/空值沿用旧密钥。 */
    private String encryptAuth(String authType, Map<String, Object> incoming, String existingEncrypted) {
        if ("NONE".equals(authType)) return null;
        Map<String, Object> auth = new LinkedHashMap<>(incoming);
        Map<String, Object> old = Map.of();
        if (existingEncrypted != null && !existingEncrypted.isBlank()) {
            try {
                String plain = cryptoService.decrypt(existingEncrypted);
                if (plain != null && !plain.isBlank()) old = objectMapper.readValue(plain, Map.class);
            } catch (Exception ignored) {
                // 旧密钥无法解密时按新配置处理
            }
        }
        for (String key : List.of("value", "token", "password")) {
            Object v = auth.get(key);
            if ((v == null || String.valueOf(v).isBlank() || MASK.equals(String.valueOf(v)))
                    && old.get(key) != null) {
                auth.put(key, old.get(key));
            }
        }
        if ("API_KEY".equals(authType) && (auth.get("header") == null || String.valueOf(auth.get("header")).isBlank())) {
            auth.put("header", "X-API-Key");
        }
        if (List.of("API_KEY", "BEARER", "OAUTH").contains(authType)) {
            String secret = "API_KEY".equals(authType) ? str(auth.get("value")) : str(auth.get("token"));
            if (secret == null || secret.isBlank()) throw new BusinessException(400, "请填写鉴权密钥");
        }
        return cryptoService.encrypt(json(auth));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> maskedAuth(McpServer s) {
        if (s.getEncryptedAuth() == null || s.getEncryptedAuth().isBlank()) return Map.of("type", s.getAuthType());
        try {
            Map<String, Object> auth = objectMapper.readValue(cryptoService.decrypt(s.getEncryptedAuth()), Map.class);
            Map<String, Object> masked = new LinkedHashMap<>(auth);
            for (String key : List.of("value", "token", "password")) {
                if (masked.get(key) != null && !String.valueOf(masked.get(key)).isBlank()) masked.put(key, MASK);
            }
            masked.put("type", s.getAuthType());
            return masked;
        } catch (Exception e) {
            return Map.of("type", s.getAuthType());
        }
    }

    private Map<String, Object> view(McpServer s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("endpoint", s.getEndpoint());
        m.put("transport", s.getTransport() == null ? "HTTP" : s.getTransport());
        m.put("authType", s.getAuthType());
        m.put("auth", maskedAuth(s));
        m.put("customHeaders", fromJsonMap(s.getCustomHeaders()));
        m.put("status", s.getStatus());
        m.put("healthStatus", s.getHealthStatus());
        m.put("lastSyncAt", s.getLastSyncAt());
        m.put("lastCheckedAt", s.getLastCheckedAt());
        m.put("toolCount", toolMapper.selectCount(new LambdaQueryWrapper<ToolRegistry>()
                .eq(ToolRegistry::getMcpServerId, s.getId())));
        m.put("createdAt", s.getCreatedAt());
        m.put("updatedAt", s.getUpdatedAt());
        return m;
    }

    private List<String> referencedAgents(Long serverId) {
        List<Long> toolIds = toolMapper.selectList(new LambdaQueryWrapper<ToolRegistry>()
                        .eq(ToolRegistry::getMcpServerId, serverId))
                .stream().map(ToolRegistry::getId).toList();
        if (toolIds.isEmpty()) return List.of();
        List<Long> agentIds = bindingMapper.selectList(new LambdaQueryWrapper<AgentToolBinding>()
                        .in(AgentToolBinding::getToolId, toolIds))
                .stream().map(AgentToolBinding::getAgentId).distinct().toList();
        if (agentIds.isEmpty()) return List.of();
        return agentMapper.selectBatchIds(agentIds).stream()
                .filter(a -> !"DELETED".equals(a.getStatus()))
                .map(AgentRegistry::getName).distinct().sorted().toList();
    }

    private List<String> referencedSkills(Long serverId) {
        List<Long> toolIds = toolMapper.selectList(new LambdaQueryWrapper<ToolRegistry>()
                        .eq(ToolRegistry::getMcpServerId, serverId))
                .stream().map(ToolRegistry::getId).toList();
        if (toolIds.isEmpty()) return List.of();
        List<Long> skillIds = new ArrayList<>();
        for (Long toolId : toolIds) {
            skillDepMapper.selectByTool(toolId).stream().map(SkillDependency::getSkillId)
                    .forEach(skillIds::add);
        }
        if (skillIds.isEmpty()) return List.of();
        Map<Long, String> names = new LinkedHashMap<>();
        for (SkillRegistry s : skillMapper.selectVisible(TenantContext.getTenantId())) {
            names.put(s.getId(), s.getName());
        }
        return skillIds.stream().distinct().map(names::get).filter(Objects::nonNull)
                .distinct().sorted().toList();
    }

    private String uniqueKey(Long serverId, String toolName) {
        String base = "mcp_" + serverId + "_" + toolName.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (base.length() > 58) base = base.substring(0, 58);
        String key = base;
        int i = 0;
        while (toolMapper.countByKey(key) > 0) {
            key = base + "_" + Integer.toHexString(UUID.randomUUID().hashCode() & 0xffff);
            if (++i > 5) break;
        }
        return key.length() > 64 ? key.substring(0, 64) : key;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
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

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new BusinessException(400, "JSON 序列化失败");
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
