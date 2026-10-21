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
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * MCP Server 管理（需求 3.15 / P1）：接入、连通性测试、工具发现/同步、引用检查、删除。
 * 鉴权信息 AES-GCM 加密存储，接口返回脱敏；同步生成的 MCP 工具进入 Tool 管理并可在 Agent 中绑定。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpServerService {

    private static final String MASK = "******";
    private static final Pattern COMMAND_ILLEGAL = Pattern.compile("[;&|`$><\"'(){}\\\\ \\n\\r\\t]");
    private static final List<String> FORBIDDEN_COMMANDS = List.of(
            "sh", "bash", "zsh", "dash", "ksh", "csh", "tcsh", "fish",
            "cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh", "pwsh.exe");

    private final McpServerMapper serverMapper;
    private final McpClientService client;
    private final McpOAuthService oauthService;
    private final ToolRegistryMapper toolMapper;
    private final ToolVersionMapper toolVersionMapper;
    private final AgentToolBindingMapper bindingMapper;
    private final SkillDependencyMapper skillDepMapper;
    private final SkillRegistryMapper skillMapper;
    private final AgentRegistryMapper agentMapper;
    private final CryptoService cryptoService;
    private final NetworkPolicyService networkPolicy;
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
        if (name == null || name.isBlank()) throw new BusinessException(400, "名称不能为空");
        String transport = normalizeTransport(req.get("transport"));
        McpServer s = new McpServer();
        s.setName(name.trim());
        s.setTransport(transport);
        if ("STDIO".equals(transport)) {
            applyStdioConfig(s, req, null);
        } else {
            String endpoint = str(req.get("endpoint"));
            validateEndpoint(endpoint);
            s.setEndpoint(endpoint.trim());
            s.setCustomHeaders(normalizeHeaders(req.get("customHeaders")));
            String authType = normalizeAuthType(req.get("authType"));
            s.setAuthType(authType);
            s.setEncryptedAuth(encryptAuth(authType, asMap(req.get("auth")), null));
        }
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
        String transport = req.get("transport") != null ? normalizeTransport(req.get("transport"))
                : (s.getTransport() == null ? "HTTP" : s.getTransport());
        s.setTransport(transport);
        if ("STDIO".equals(transport)) {
            applyStdioConfig(s, req, s);
            s.setAuthType("NONE");
            s.setEncryptedAuth("");
            s.setCustomHeaders("");
        } else {
            s.setCommand("");
            s.setArgs("");
            s.setEnvEncrypted("");
            if (req.get("endpoint") != null) {
                String endpoint = str(req.get("endpoint"));
                validateEndpoint(endpoint);
                s.setEndpoint(endpoint.trim());
            }
            if (req.containsKey("customHeaders")) s.setCustomHeaders(normalizeHeaders(req.get("customHeaders")));
            if (req.get("authType") != null) {
                String authType = normalizeAuthType(req.get("authType"));
                s.setAuthType(authType);
                s.setEncryptedAuth(encryptAuth(authType, asMap(req.get("auth")), s.getEncryptedAuth()));
            } else if (req.get("auth") != null) {
                s.setEncryptedAuth(encryptAuth(s.getAuthType(), asMap(req.get("auth")), s.getEncryptedAuth()));
            }
        }
        s.setUpdatedAt(LocalDateTime.now());
        serverMapper.updateById(s);
        client.closeServer(id);
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
                m.put("outputSchema", parseJson(t.outputSchema()));
                m.put("annotations", parseJson(t.annotations()));
                rows.add(m);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("tools", rows);
            result.put("total", rows.size());

            // resources/prompts 是可选能力；老 Server 返回 method not found 时降级为空，不影响工具发现
            List<String> warnings = new ArrayList<>();
            try {
                List<Map<String, Object>> resources = new ArrayList<>();
                for (McpClientService.McpResource r : client.listResources(s)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("uri", r.uri());
                    m.put("name", r.name());
                    m.put("description", r.description());
                    m.put("mimeType", r.mimeType());
                    m.put("size", r.size());
                    resources.add(m);
                }
                result.put("resources", resources);

                List<Map<String, Object>> templates = new ArrayList<>();
                for (McpClientService.McpResourceTemplate t : client.listResourceTemplates(s)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("uriTemplate", t.uriTemplate());
                    m.put("name", t.name());
                    m.put("description", t.description());
                    m.put("mimeType", t.mimeType());
                    templates.add(m);
                }
                result.put("resourceTemplates", templates);
            } catch (Exception e) {
                result.put("resources", List.of());
                result.put("resourceTemplates", List.of());
                warnings.add("resources: " + rootMessage(e));
            }
            try {
                List<Map<String, Object>> prompts = new ArrayList<>();
                for (McpClientService.McpPrompt p : client.listPrompts(s)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", p.name());
                    m.put("description", p.description());
                    m.put("arguments", parseJson(p.argumentsJson()));
                    prompts.add(m);
                }
                result.put("prompts", prompts);
            } catch (Exception e) {
                result.put("prompts", List.of());
                warnings.add("prompts: " + rootMessage(e));
            }
            result.put("toolsChangedAt", client.toolsChangedAt(s.getId()));
            result.put("warnings", warnings);
            return result;
        } catch (Exception e) {
            updateHealth(s, "OFFLINE");
            throw new BusinessException(400, "工具发现失败：" + rootMessage(e));
        }
    }

    /** 读取 MCP resource（内容是不受信数据，由前端/调用方决定如何展示）。 */
    public Map<String, Object> readResource(Long id, String uri) {
        McpServer s = visible(id);
        if (uri == null || uri.isBlank()) throw new BusinessException(400, "resource uri 不能为空");
        try {
            List<Map<String, Object>> contents = new ArrayList<>();
            for (McpClientService.McpResourceContent c : client.readResource(s, uri.trim())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("uri", c.uri());
                m.put("mimeType", c.mimeType());
                m.put("text", c.text());
                m.put("blob", c.blob());
                contents.add(m);
            }
            return Map.of("uri", uri.trim(), "contents", contents);
        } catch (Exception e) {
            throw new BusinessException(400, "读取 resource 失败：" + rootMessage(e));
        }
    }

    /** 获取 MCP prompt 渲染结果。 */
    public Map<String, Object> getPrompt(Long id, String name, Map<String, Object> arguments) {
        McpServer s = visible(id);
        if (name == null || name.isBlank()) throw new BusinessException(400, "prompt name 不能为空");
        try {
            McpClientService.McpPromptResult result = client.getPrompt(s, name.trim(), arguments);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name.trim());
            m.put("description", result.description());
            m.put("messages", parseJson(result.messagesJson()));
            return m;
        } catch (Exception e) {
            throw new BusinessException(400, "获取 prompt 失败：" + rootMessage(e));
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
            // 2025-03-26：structuredContent/outputSchema 与 tool annotations（readOnlyHint 等）落库
            Object outputSchema = parseJson(t.outputSchema());
            if (outputSchema != null) cfg.put("outputSchema", outputSchema);
            Object annotations = parseJson(t.annotations());
            if (annotations != null) cfg.put("annotations", annotations);
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
        client.markToolsSynced(id);
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
        client.closeServer(id);
        auditService.record(principal.userId(), "mcp_server.delete", "mcp_server", String.valueOf(id),
                "SUCCESS", Map.of("name", s.getName(), "tools", tools.size()));
    }

    // ---------- OAuth 2.1 ----------

    public Map<String, Object> oauthAuthorize(Long id, JwtPrincipal principal) {
        return oauthService.authorizeUrl(visible(id), principal);
    }

    public String oauthCallback(String code, String state, String error, String errorDescription) {
        return oauthService.callback(code, state, error, errorDescription);
    }

    public Map<String, Object> oauthStatus(Long id) {
        return oauthService.status(visible(id));
    }

    public void oauthDisconnect(Long id, JwtPrincipal principal) {
        McpServer s = visible(id);
        oauthService.disconnect(s);
        auditService.record(principal.userId(), "mcp_server.oauth.disconnect", "mcp_server",
                String.valueOf(id), "SUCCESS", Map.of("name", s.getName()));
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
        networkPolicy.validateHttpUrl(endpoint, "Server 地址");
    }

    private String normalizeTransport(Object raw) {
        String transport = raw == null ? "HTTP" : String.valueOf(raw).toUpperCase(Locale.ROOT);
        if (!List.of("HTTP", "SSE", "STDIO").contains(transport)) {
            throw new BusinessException(400, "传输方式仅支持 HTTP/SSE/STDIO");
        }
        return transport;
    }

    /** STDIO 配置：命令/参数/环境变量（env AES-GCM 加密；更新未传则沿用旧值）。 */
    private void applyStdioConfig(McpServer s, Map<String, Object> req, McpServer existing) {
        String command = req.get("command") != null ? normalizeCommand(req.get("command"))
                : (existing == null ? null : existing.getCommand());
        if (command == null || command.isBlank()) throw new BusinessException(400, "STDIO 需要配置启动命令");
        s.setCommand(command);
        if (req.containsKey("args")) {
            List<String> args = normalizeArgs(req.get("args"));
            s.setArgs(args.isEmpty() ? "" : json(args));
        } else {
            s.setArgs(existing == null ? "" : existing.getArgs());
        }
        if (req.containsKey("env")) {
            s.setEnvEncrypted(encryptEnv(req.get("env"), existing == null ? null : existing.getEnvEncrypted()));
        } else {
            s.setEnvEncrypted(existing == null ? "" : existing.getEnvEncrypted());
        }
        List<String> args = normalizeArgs(s.getArgs());
        String display = "stdio://" + command + (args.isEmpty() ? "" : " " + String.join(" ", args));
        s.setEndpoint(display.length() > 250 ? display.substring(0, 250) : display);
    }

    /** 命令注入防护：无 shell（ProcessBuilder 数组执行）、禁特殊字符/空格、禁 shell 解释器。 */
    private String normalizeCommand(Object raw) {
        String command = str(raw);
        if (command == null || command.isBlank()) throw new BusinessException(400, "STDIO 启动命令不能为空");
        command = command.trim();
        if (command.length() > 256) throw new BusinessException(400, "STDIO 启动命令过长");
        if (COMMAND_ILLEGAL.matcher(command).find()) {
            throw new BusinessException(400, "STDIO 启动命令不能包含空格或 shell 特殊字符");
        }
        String base = command.substring(command.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (FORBIDDEN_COMMANDS.contains(base)) {
            throw new BusinessException(400, "禁止以 shell 解释器作为 MCP stdio 命令：" + base);
        }
        return command;
    }

    private List<String> normalizeArgs(Object raw) {
        if (raw == null || (raw instanceof String text && text.isBlank())) return List.of();
        List<?> list;
        if (raw instanceof List<?> l) {
            list = l;
        } else {
            try {
                list = objectMapper.readValue(String.valueOf(raw), List.class);
            } catch (Exception e) {
                throw new BusinessException(400, "STDIO 参数需为 JSON 数组");
            }
        }
        List<String> args = new ArrayList<>();
        for (Object item : list) {
            String arg = item == null ? "" : String.valueOf(item);
            if (arg.contains("\n") || arg.contains("\r")) throw new BusinessException(400, "STDIO 参数不允许换行");
            if (arg.length() > 4096) throw new BusinessException(400, "STDIO 参数过长");
            args.add(arg);
        }
        if (args.size() > 64) throw new BusinessException(400, "STDIO 参数数量过多");
        return args;
    }

    private String encryptEnv(Object incoming, String existingEncrypted) {
        Map<String, Object> old = decryptMap(existingEncrypted);
        Map<String, Object> env;
        if (incoming instanceof Map<?, ?> m) {
            env = asMap(m);
        } else {
            try {
                env = asMap(objectMapper.readValue(String.valueOf(incoming), Map.class));
            } catch (Exception e) {
                throw new BusinessException(400, "环境变量需为 JSON 对象");
            }
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : env.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().trim();
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new BusinessException(400, "环境变量名不合法：" + key);
            }
            Object value = e.getValue();
            if (value != null && MASK.equals(String.valueOf(value)) && old.containsKey(key)) {
                value = old.get(key);
            }
            String text = value == null ? "" : String.valueOf(value);
            if (text.length() > 8192) throw new BusinessException(400, "环境变量值过长：" + key);
            merged.put(key, text);
        }
        return merged.isEmpty() ? "" : cryptoService.encrypt(json(merged));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decryptMap(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) return Map.of();
        try {
            Map<String, Object> map = objectMapper.readValue(cryptoService.decrypt(encrypted), Map.class);
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> listOfJson(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return objectMapper.readValue(raw, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private Object parseJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return raw;
        }
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

    /** 加密鉴权配置；更新时脱敏占位/空值沿用旧密钥。OAuth 额外保留 client/token 字段并允许先不授权。 */
    private String encryptAuth(String authType, Map<String, Object> incoming, String existingEncrypted) {
        if ("NONE".equals(authType)) return null;
        Map<String, Object> old = decryptMap(existingEncrypted);
        if ("OAUTH".equals(authType)) {
            Map<String, Object> merged = new LinkedHashMap<>(old);
            for (Map.Entry<String, Object> e : incoming.entrySet()) {
                String key = e.getKey();
                Object value = e.getValue();
                if (key == null || value == null) continue;
                if (MASK.equals(String.valueOf(value)) && old.containsKey(key)) continue;
                merged.put(key, value);
            }
            return merged.isEmpty() ? null : cryptoService.encrypt(json(merged));
        }
        Map<String, Object> auth = new LinkedHashMap<>(incoming);
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
        if (List.of("API_KEY", "BEARER").contains(authType)) {
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
            for (String key : List.of("value", "token", "password", "access_token",
                    "refresh_token", "client_secret")) {
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
        m.put("command", s.getCommand());
        m.put("args", listOfJson(s.getArgs()));
        m.put("envKeys", new ArrayList<>(decryptMap(s.getEnvEncrypted()).keySet()));
        m.put("authType", s.getAuthType());
        m.put("auth", maskedAuth(s));
        m.put("oauth", oauthService.status(s));
        m.put("customHeaders", fromJsonMap(s.getCustomHeaders()));
        m.put("status", s.getStatus());
        m.put("healthStatus", s.getHealthStatus());
        m.put("lastSyncAt", s.getLastSyncAt());
        m.put("toolsChangedAt", client.toolsChangedAt(s.getId()));
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
