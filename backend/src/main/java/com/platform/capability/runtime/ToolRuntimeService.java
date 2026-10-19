package com.platform.capability.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.AgentKnowledgeBase;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.entity.AgentSubAgent;
import com.platform.agent.entity.AgentToolBinding;
import com.platform.agent.entity.AgentVersion;
import com.platform.agent.mapper.AgentKnowledgeBaseMapper;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.agent.mapper.AgentSkillBindingMapper;
import com.platform.agent.mapper.AgentSubAgentMapper;
import com.platform.agent.mapper.AgentToolBindingMapper;
import com.platform.agent.mapper.AgentVersionMapper;
import com.platform.agent.entity.McpServer;
import com.platform.agent.mapper.McpServerMapper;
import com.platform.agent.service.AgentCredentialService;
import com.platform.agent.service.McpClientService;
import com.platform.capability.entity.SkillDependency;
import com.platform.capability.entity.SkillRegistry;
import com.platform.capability.entity.ToolRegistry;
import com.platform.capability.mapper.SkillDependencyMapper;
import com.platform.capability.mapper.SkillRegistryMapper;
import com.platform.capability.mapper.ToolRegistryMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.security.CryptoService;
import com.platform.knowledge.service.KnowledgePermissionService;
import com.platform.security.JwtPrincipal;
import com.platform.system.entity.AgentUserGrant;
import com.platform.system.mapper.AgentUserGrantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Agent 运行时能力装配（需求 3.1.3(4)(8)、3.13、3.14）：
 * 技能提示词注入 + 内置/API 工具回调 + 子 Agent 工具；工具调用统一采集为 Span。
 * 子 Agent 循环与深度在装配阶段阻断（保存期再由 AgentService 做静态环检测）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolRuntimeService {

    private static final int MAX_SUB_AGENT_DEPTH = 3;
    private static final int MAX_TOOL_EVENT_OUTPUT = 4000;

    @org.springframework.beans.factory.annotation.Value("${tool.max-output-chars:8000}")
    private int maxToolOutput;
    private static final String EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    private final ToolRegistryMapper toolMapper;
    private final SkillRegistryMapper skillMapper;
    private final SkillDependencyMapper skillDepMapper;
    private final AgentToolBindingMapper agentToolMapper;
    private final AgentSkillBindingMapper agentSkillMapper;
    private final AgentSubAgentMapper subAgentMapper;
    private final AgentKnowledgeBaseMapper agentKbMapper;
    private final AgentRegistryMapper agentMapper;
    private final AgentVersionMapper versionMapper;
    private final AgentUserGrantMapper grantMapper;
    private final McpServerMapper mcpServerMapper;
    private final McpClientService mcpClient;
    private final AgentCredentialService credentialService;
    private final com.platform.agent.service.ContextWindowService contextWindow;
    private final CryptoService cryptoService;
    private final com.platform.model.service.ChatModelRouter chatModelRouter;
    private final com.platform.knowledge.service.HybridRetrievalService hybridRetrieval;
    private final ScriptRunnerService scriptRunner;
    private final KnowledgePermissionService permissionService;
    private final ObjectMapper objectMapper;

    /** 装配结果：工具回调 + 技能提示词 + 技能依赖的知识库。 */
    public record RuntimeTools(List<ToolCallback> callbacks, String skillPrompt, List<Long> knowledgeBaseIds) {
        public static RuntimeTools empty() {
            return new RuntimeTools(List.of(), null, List.of());
        }
    }

    public RuntimeTools build(Long agentId, Map<String, Object> config, JwtPrincipal principal,
                              KnowledgePermissionService.Viewer viewer, ToolCallCollector collector,
                              Map<String, Object> variables) {
        return build(agentId, config, principal, viewer, collector, variables, 0, new HashSet<>());
    }

    // ---------- 装配 ----------

    private RuntimeTools build(Long agentId, Map<String, Object> config, JwtPrincipal principal,
                               KnowledgePermissionService.Viewer viewer, ToolCallCollector collector,
                               Map<String, Object> variables, int depth, Set<Long> visited) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) return RuntimeTools.empty();

        Map<String, Object> caps = asMap(config == null ? null : config.get("agentCapabilities"));
        boolean functionCall = Boolean.TRUE.equals(caps.get("functionCall"))
                || "true".equals(String.valueOf(caps.get("functionCall")));
        boolean hasTools = functionCall && depth < MAX_SUB_AGENT_DEPTH + 1;

        List<ToolCallback> callbacks = new ArrayList<>();
        Set<String> names = new HashSet<>();
        List<Long> kbIds = new ArrayList<>();
        StringBuilder skillPrompt = new StringBuilder();

        // 1) 技能：提示词始终注入；依赖的工具/知识库按启用状态装配
        for (Object skillRef : refs(agentId, config, "skills")) {
            Long skillId = toLong(asMap(skillRef).get("skillId"));
            if (skillId == null || Boolean.FALSE.equals(asMap(skillRef).get("enabled"))) continue;
            SkillRegistry skill = skillMapper.findVisible(skillId, tenantId);
            if (skill == null || !"ENABLED".equals(skill.getStatus())) {
                log.info("技能不可用，运行时跳过：skillId={}", skillId);
                continue;
            }
            if (skill.getPrompt() != null && !skill.getPrompt().isBlank()) {
                skillPrompt.append("\n\n【技能：").append(skill.getName()).append("】\n")
                        .append(applyVariables(skill.getPrompt(), skill.getParams(), variables));
            }
            if (hasTools && hasScript(skill)) {
                addSkillScript(callbacks, names, skill, collector);
            }
            for (SkillDependency dep : skillDepMapper.selectBySkill(skillId)) {
                if ("KNOWLEDGE_BASE".equals(dep.getDepType())) {
                    if (!kbIds.contains(dep.getDepId())) kbIds.add(dep.getDepId());
                } else if ("TOOL".equals(dep.getDepType()) && hasTools) {
                    addTool(callbacks, names, dep.getDepId(), agentId, tenantId, collector, principal, viewer,
                            variables, depth, visited);
                }
            }
        }

        // 2) Agent 绑定的工具
        if (hasTools) {
            for (Object toolRef : refs(agentId, config, "tools")) {
                Map<String, Object> ref = asMap(toolRef);
                if (Boolean.FALSE.equals(ref.get("enabled"))) continue;
                Long toolId = toLong(ref.get("toolId"));
                if (toolId != null) {
                    addTool(callbacks, names, toolId, agentId, tenantId, collector, principal, viewer,
                            variables, depth, visited);
                }
            }
        }

        // 3) 子 Agent（作为工具调用）
        if (functionCall && depth < MAX_SUB_AGENT_DEPTH) {
            for (Object subRef : refs(agentId, config, "subAgents")) {
                Map<String, Object> ref = asMap(subRef);
                if (Boolean.FALSE.equals(ref.get("enabled"))) continue;
                Long subId = toLong(ref.get("subAgentId"));
                if (subId == null || subId.equals(agentId) || visited.contains(subId)) continue;
                AgentRegistry sub = agentMapper.selectById(subId);
                if (sub == null || "DELETED".equals(sub.getStatus())) {
                    log.info("子 Agent 不可用，运行时跳过：agentId={}", subId);
                    continue;
                }
                if (!usable(sub, principal)) {
                    log.info("子 Agent 无使用权限，运行时跳过：agentId={} user={}", subId, principal.userId());
                    continue;
                }
                String name = "agent_" + subId;
                if (!names.add(name)) continue;
                callbacks.add(subAgentCallback(sub, name, principal, viewer, collector, variables, depth + 1, visited));
            }
        }

        log.debug("Agent 运行时工具装配 agent={} functionCall={} callbacks={} names={} skillPrompt={} kbDeps={}",
                agentId, functionCall, callbacks.size(), names,
                skillPrompt.isEmpty() ? "none" : "yes", kbIds);
        return new RuntimeTools(callbacks, skillPrompt.isEmpty() ? null : skillPrompt.toString(), kbIds);
    }

    private void addTool(List<ToolCallback> callbacks, Set<String> names, Long toolId, Long agentId,
                         Long tenantId, ToolCallCollector collector, JwtPrincipal principal,
                         KnowledgePermissionService.Viewer viewer, Map<String, Object> variables,
                         int depth, Set<Long> visited) {
        ToolRegistry tool = toolMapper.findVisible(toolId, tenantId);
        if (tool == null || !"ENABLED".equals(tool.getStatus())) {
            log.info("工具不可用，运行时跳过：toolId={}", toolId);
            return;
        }
        String toolName = tool.getToolKey();
        if (toolName == null || !toolName.matches("[a-zA-Z0-9_-]{1,64}")) {
            log.warn("工具 key 非法，跳过：{}", toolName);
            return;
        }
        if (!names.add(toolName)) return;
        callbacks.add(switch (tool.getToolType() == null ? "API" : tool.getToolType()) {
            case "BUILTIN" -> builtinCallback(tool, collector);
            case "API" -> apiCallback(tool, collector, agentId);
            case "MCP" -> mcpCallback(tool, collector);
            default -> {
                log.info("未知工具类型，跳过：{}", tool.getToolKey());
                yield null;
            }
        });
        callbacks.removeIf(java.util.Objects::isNull);
    }

    private boolean hasScript(SkillRegistry skill) {
        return ("PYTHON".equals(skill.getScriptLanguage()) || "JAVASCRIPT".equals(skill.getScriptLanguage()))
                && skill.getScriptCode() != null && !skill.getScriptCode().isBlank();
    }

    /** 技能脚本注册为工具，模型可像普通工具一样调用。 */
    private void addSkillScript(List<ToolCallback> callbacks, Set<String> names,
                                SkillRegistry skill, ToolCallCollector collector) {
        String toolName = "skill_" + skill.getId();
        if (!toolName.matches("[a-zA-Z0-9_-]{1,64}") || !names.add(toolName)) return;
        String schema = scriptRunner.inputSchema(skill.getParams());
        int timeout = skill.getScriptTimeoutMs() == null ? 5000 : skill.getScriptTimeoutMs();
        String description = (skill.getDescription() == null || skill.getDescription().isBlank()
                ? "执行技能「" + skill.getName() + "」的脚本逻辑。"
                : skill.getDescription() + "（技能「" + skill.getName() + "」脚本工具）");
        callbacks.add(new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(toolName)
                        .description(description)
                        .inputSchema(schema)
                        .build();
            }

            @Override
            public String call(String toolInput) {
                long start = System.currentTimeMillis();
                String status = "SUCCESS";
                String output;
                try {
                    Map<String, Object> args = scriptRunner.withDefaults(skill.getParams(), args(toolInput));
                    ScriptRunnerService.Result result = scriptRunner.run(skill.getScriptLanguage(),
                            skill.getScriptCode(), args, timeout);
                    output = result.output().isBlank() ? "脚本执行完成（无输出）" : result.output();
                    if (!result.stderr().isBlank()) {
                        output = output + "\n[stderr] " + result.stderr();
                    }
                } catch (Exception e) {
                    status = "ERROR";
                    output = "技能脚本执行失败：" + rootMessage(e);
                }
                String modelOutput = limitModelOutput(output);
                long end = System.currentTimeMillis();
                collector.add(new ToolCallEvent(toolName, skill.getName(), "SKILL",
                        null, null, status, end - start,
                        truncate(toolInput, 4000), truncate(output, MAX_TOOL_EVENT_OUTPUT),
                        0, 0, start, end));
                return modelOutput;
            }
        });
    }

    /** 数据库绑定（agentId 非空）或调试配置中的数组。 */
    private List<Object> refs(Long agentId, Map<String, Object> config, String type) {
        if (agentId != null) {
            Long tenantId = TenantContext.getTenantId();
            List<Map<String, Object>> rows = new ArrayList<>();
            if ("tools".equals(type)) {
                for (AgentToolBinding b : agentToolMapper.selectList(new LambdaQueryWrapper<AgentToolBinding>()
                        .eq(AgentToolBinding::getAgentId, agentId))) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("toolId", b.getToolId());
                    m.put("enabled", b.getEnabled() != null && b.getEnabled() == 1);
                    m.put("params", fromJsonMap(b.getParams()));
                    rows.add(m);
                }
            } else if ("skills".equals(type)) {
                agentSkillMapper.selectList(new LambdaQueryWrapper<com.platform.agent.entity.AgentSkillBinding>()
                                .eq(com.platform.agent.entity.AgentSkillBinding::getAgentId, agentId))
                        .forEach(b -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("skillId", b.getSkillId());
                            m.put("enabled", b.getEnabled() != null && b.getEnabled() == 1);
                            rows.add(m);
                        });
            } else {
                for (AgentSubAgent b : subAgentMapper.selectList(new LambdaQueryWrapper<AgentSubAgent>()
                        .eq(AgentSubAgent::getAgentId, agentId))) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("subAgentId", b.getSubAgentId());
                    m.put("enabled", b.getEnabled() != null && b.getEnabled() == 1);
                    rows.add(m);
                }
            }
            return List.copyOf(rows);
        }
        // 调试链路：未保存配置直接来自表单
        Object value = config == null ? null : config.get(type);
        return value instanceof List<?> list ? List.copyOf(list) : List.of();
    }

    /** 工作流工具节点：按 Agent 解析凭证后执行一次工具。 */
    public Map<String, Object> executeToolForWorkflow(Long agentId, Long toolId, String argsJson) {
        ToolRegistry tool = toolMapper.findVisible(toolId, TenantContext.getTenantId());
        if (tool == null || !"ENABLED".equals(tool.getStatus())) {
            throw new IllegalArgumentException("工具不存在或未启用");
        }
        ToolCallCollector collector = new ToolCallCollector();
        ToolCallback callback = switch (tool.getToolType() == null ? "API" : tool.getToolType()) {
            case "BUILTIN" -> builtinCallback(tool, collector);
            case "API" -> apiCallback(tool, collector, agentId);
            case "MCP" -> mcpCallback(tool, collector);
            default -> null;
        };
        if (callback == null) throw new IllegalArgumentException("暂不支持该工具类型");
        long start = System.currentTimeMillis();
        String output = callback.call(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
        return Map.of("output", output == null ? "" : output,
                "latencyMs", System.currentTimeMillis() - start,
                "toolCalls", collector.events());
    }

    /** 管理页工具测试：直接执行一次（不写 Trace、不经过 LLM）。 */
    public Map<String, Object> testTool(ToolRegistry tool, String argsJson) {
        ToolCallCollector collector = new ToolCallCollector();
        ToolCallback callback = switch (tool.getToolType() == null ? "API" : tool.getToolType()) {
            case "BUILTIN" -> builtinCallback(tool, collector);
            case "API" -> apiCallback(tool, collector, null);
            case "MCP" -> mcpCallback(tool, collector);
            default -> null;
        };
        if (callback == null) throw new IllegalArgumentException("暂不支持该工具类型");
        long start = System.currentTimeMillis();
        String output = callback.call(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
        return Map.of("output", output, "latencyMs", System.currentTimeMillis() - start,
                "toolCalls", collector.events());
    }

    // ---------- 内置工具 ----------

    private ToolCallback builtinCallback(ToolRegistry tool, ToolCallCollector collector) {
        String schema = schemaOf(tool);
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(tool.getToolKey())
                        .description(tool.getDescription() == null ? tool.getName() : tool.getDescription())
                        .inputSchema(schema)
                        .build();
            }

            @Override
            public String call(String toolInput) {
                long start = System.currentTimeMillis();
                String status = "SUCCESS";
                String output;
                try {
                    Map<String, Object> args = args(toolInput);
                    output = switch (tool.getToolKey()) {
                        case "calculator" -> "计算结果：" + Calculator.eval(str(args.get("expression")));
                        case "current_time" -> currentTime(str(args.get("timezone")));
                        default -> "未实现的内置工具：" + tool.getToolKey();
                    };
                } catch (Exception e) {
                    status = "ERROR";
                    output = "工具执行失败：" + rootMessage(e);
                }
                String modelOutput = limitModelOutput(output);
                long end = System.currentTimeMillis();
                collector.add(new ToolCallEvent(tool.getToolKey(), tool.getName(), "BUILTIN",
                        null, null, status, end - start,
                        truncate(toolInput, 4000), truncate(output, MAX_TOOL_EVENT_OUTPUT), 0, 0, start, end));
                return modelOutput;
            }
        };
    }

    private String currentTime(String timezone) {
        ZoneId zone;
        try {
            zone = timezone == null || timezone.isBlank() ? ZoneId.of("Asia/Shanghai") : ZoneId.of(timezone);
        } catch (Exception e) {
            throw new IllegalArgumentException("非法时区：" + timezone);
        }
        LocalDateTime now = LocalDateTime.now(zone);
        return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " " + zone
                + "（" + now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA) + "）";
    }

    // ---------- API 工具 ----------

    private ToolCallback apiCallback(ToolRegistry tool, ToolCallCollector collector, Long agentId) {
        Map<String, Object> cfg = fromJsonMap(tool.getApiConfig());
        String schema = schemaOf(tool);
        int timeout = tool.getTimeoutMs() == null ? 10000 : Math.max(tool.getTimeoutMs(), 500);
        int retries = tool.getRetryTimes() == null ? 0 : Math.min(Math.max(tool.getRetryTimes(), 0), 3);
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(tool.getToolKey())
                        .description(tool.getDescription() == null ? tool.getName() : tool.getDescription())
                        .inputSchema(schema)
                        .build();
            }

            @Override
            public String call(String toolInput) {
                long start = System.currentTimeMillis();
                String status = "SUCCESS";
                String output;
                try {
                    String credential = null;
                    String credentialType = null;
                    if (agentId != null) {
                        credential = credentialService.resolveContent(agentId, tool.getId());
                        credentialType = credentialService.typeOf(agentId, tool.getId());
                    }
                    output = executeApi(cfg, args(toolInput), timeout, retries, credential, credentialType);
                } catch (Exception e) {
                    status = "ERROR";
                    output = "工具执行失败：" + rootMessage(e);
                }
                String modelOutput = limitModelOutput(output);
                long end = System.currentTimeMillis();
                collector.add(new ToolCallEvent(tool.getToolKey(), tool.getName(), "API",
                        null, null, status, end - start,
                        truncate(toolInput, 4000), truncate(output, MAX_TOOL_EVENT_OUTPUT), 0, 0, start, end));
                return modelOutput;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private String executeApi(Map<String, Object> cfg, Map<String, Object> args,
                              int timeoutMs, int retries, String credential, String credentialType) throws Exception {
        String url = str(cfg.get("url"));
        if (url == null || url.isBlank()) throw new IllegalArgumentException("API 地址未配置");
        URI uri = URI.create(substitutePath(url, args));
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("仅支持 http/https 地址");
        }
        String method = str(cfg.getOrDefault("method", "GET")).toUpperCase(Locale.ROOT);

        // 必填参数校验（参数 Schema）
        List<String> missing = new ArrayList<>();
        if (cfg.get("params") instanceof List<?> paramDefs) {
            for (Object o : paramDefs) {
                Map<String, Object> p = asMap(o);
                String name = str(p.get("name"));
                boolean required = Boolean.TRUE.equals(p.get("required"))
                        || "true".equals(String.valueOf(p.get("required")));
                if (required && (args.get(name) == null || String.valueOf(args.get(name)).isBlank())) {
                    missing.add(name);
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("缺少必填参数：" + String.join(", ", missing));
        }

        // query 参数：params 中 in=query（未声明 in 时默认 query）
        StringBuilder query = new StringBuilder();
        Map<String, Object> bodyArgs = new LinkedHashMap<>(args);
        if (cfg.get("params") instanceof List<?> params) {
            for (Object o : params) {
                Map<String, Object> p = asMap(o);
                String name = str(p.get("name"));
                if (name == null || !args.containsKey(name)) continue;
                if (!"query".equalsIgnoreCase(str(p.getOrDefault("in", "query")))) continue;
                if (query.length() > 0) query.append('&');
                query.append(URLEncoder.encode(name, StandardCharsets.UTF_8)).append('=')
                        .append(URLEncoder.encode(String.valueOf(args.get(name)), StandardCharsets.UTF_8));
                bodyArgs.remove(name);
            }
        }
        if (query.length() > 0) {
            uri = URI.create(uri.toString() + (uri.toString().contains("?") ? "&" : "?") + query);
        }

        String bodyTemplate = str(cfg.get("bodyTemplate"));
        String body = null;
        if (bodyTemplate != null && !bodyTemplate.isBlank()) {
            body = substitute(bodyTemplate, args);
        } else if (List.of("POST", "PUT", "PATCH", "DELETE").contains(method) && !bodyArgs.isEmpty()) {
            body = objectMapper.writeValueAsString(bodyArgs);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeoutMs));
        Map<String, Object> headers = asMap(cfg.get("headers"));
        for (Map.Entry<String, Object> h : headers.entrySet()) {
            builder.header(h.getKey(), substitute(String.valueOf(h.getValue()), args));
        }
        if (credential != null && !credential.isBlank()) {
            String type = credentialType == null ? "API_KEY" : credentialType.toUpperCase();
            switch (type) {
                case "OAUTH" -> builder.header("Authorization", "Bearer " + credential);
                case "BASIC" -> builder.header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(credential.getBytes(StandardCharsets.UTF_8)));
                default -> builder.header("X-API-Key", credential);
            }
        } else {
            applyAuth(builder, decryptAuth(asMap(cfg.get("auth"))));
        }
        if (body != null) {
            if (headers.keySet().stream().noneMatch(k -> "content-type".equalsIgnoreCase(k))) {
                builder.header("Content-Type", "application/json");
            }
            builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
        Exception last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                HttpResponse<String> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                if ((resp.statusCode() >= 500 || resp.statusCode() == 429) && attempt < retries) {
                    last = new IllegalStateException("HTTP " + resp.statusCode());
                    sleepBackoff(attempt);
                    continue;
                }
                if (resp.statusCode() >= 400) {
                    throw new IllegalStateException("HTTP " + resp.statusCode() + "："
                            + truncate(resp.body(), 300));
                }
                return extractResponse(resp.body(), str(cfg.get("responsePath")));
            } catch (Exception e) {
                last = e;
                if (attempt < retries) sleepBackoff(attempt);
            }
        }
        throw last == null ? new IllegalStateException("请求失败") : last;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decryptAuth(Map<String, Object> auth) {
        Map<String, Object> result = new LinkedHashMap<>(auth);
        for (String key : List.of("value", "token", "password")) {
            Object v = result.get(key);
            if (v != null && String.valueOf(v).startsWith("v1:")) {
                result.put(key, cryptoService.decrypt(String.valueOf(v)));
            }
        }
        return result;
    }

    private void applyAuth(HttpRequest.Builder builder, Map<String, Object> auth) {
        String type = str(auth.getOrDefault("type", "NONE")).toUpperCase(Locale.ROOT);
        switch (type) {
            case "API_KEY" -> builder.header(
                    str(auth.getOrDefault("header", "X-API-Key")), str(auth.get("value")));
            case "BEARER" -> builder.header("Authorization", "Bearer " + str(auth.get("token")));
            case "BASIC" -> {
                String raw = str(auth.getOrDefault("username", "")) + ":" + str(auth.getOrDefault("password", ""));
                builder.header("Authorization", "Basic "
                        + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
            }
            default -> { }
        }
    }

    private String extractResponse(String body, String path) throws Exception {
        if (body == null) return "";
        if (path == null || path.isBlank()) return truncate(body, MAX_TOOL_EVENT_OUTPUT);
        JsonNode node = objectMapper.readTree(body);
        for (String seg : path.split("\\.")) {
            if (node == null) return "";
            node = node.isArray() && seg.matches("\\d+") ? node.get(Integer.parseInt(seg)) : node.get(seg);
        }
        if (node == null || node.isNull()) return "";
        return truncate(node.isValueNode() ? node.asText() : node.toString(), MAX_TOOL_EVENT_OUTPUT);
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(300L * (attempt + 1) + ThreadLocalRandom.current().nextLong(100));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- MCP 工具 ----------

    private ToolCallback mcpCallback(ToolRegistry tool, ToolCallCollector collector) {
        Long serverId = tool.getMcpServerId();
        if (serverId == null) return null;
        McpServer server = mcpServerMapper.selectById(serverId);
        if (server == null || !"ENABLED".equals(server.getStatus())) {
            log.info("MCP Server 不可用，运行时跳过：tool={} server={}", tool.getToolKey(), serverId);
            return null;
        }
        Map<String, Object> cfg = fromJsonMap(tool.getApiConfig());
        String remoteName = str(cfg.get("toolName"));
        if (remoteName == null || remoteName.isBlank()) remoteName = tool.getName();
        String schema = schemaOf(tool);
        String finalRemoteName = remoteName;
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(tool.getToolKey())
                        .description(tool.getDescription() == null ? tool.getName() : tool.getDescription())
                        .inputSchema(schema)
                        .build();
            }

            @Override
            public String call(String toolInput) {
                long start = System.currentTimeMillis();
                String status = "SUCCESS";
                String output;
                try {
                    Map<String, Object> args = args(toolInput);
                    McpClientService.McpCallResult result = TenantContext.runWith(
                            TenantContext.getTenantId(),
                            () -> mcpClient.callTool(server, finalRemoteName, args));
                    output = result.text();
                    if (result.error()) status = "ERROR";
                } catch (Exception e) {
                    status = "ERROR";
                    output = "MCP 工具执行失败：" + rootMessage(e);
                }
                String modelOutput = limitModelOutput(output);
                long end = System.currentTimeMillis();
                collector.add(new ToolCallEvent(tool.getToolKey(), tool.getName(), "MCP",
                        null, null, status, end - start,
                        truncate(toolInput, 4000), truncate(output, MAX_TOOL_EVENT_OUTPUT), 0, 0, start, end));
                return modelOutput;
            }
        };
    }

    // ---------- 子 Agent ----------

    private ToolCallback subAgentCallback(AgentRegistry sub, String name, JwtPrincipal principal,
                                          KnowledgePermissionService.Viewer viewer,
                                          ToolCallCollector collector, Map<String, Object> variables,
                                          int depth, Set<Long> visited) {
        String schema = "{\"type\":\"object\",\"properties\":{\"task\":{\"type\":\"string\","
                + "\"description\":\"交给子 Agent 的完整任务描述，包含必要上下文\"}},\"required\":[\"task\"]}";
        String description = "子 Agent「" + sub.getName() + "」"
                + (sub.getDescription() == null ? "" : "：" + sub.getDescription())
                + "。当该子 Agent 的能力适合用户需求时调用，task 传入完整任务描述。";
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(description).inputSchema(schema).build();
            }

            @Override
            public String call(String toolInput) {
                long start = System.currentTimeMillis();
                String status = "SUCCESS";
                String output;
                long[] usage = {0, 0};
                try {
                    String task = str(args(toolInput).get("task"));
                    if (task == null || task.isBlank()) throw new IllegalArgumentException("task 不能为空");
                    Long tenantId = TenantContext.getTenantId();
                    output = TenantContext.runWith(tenantId, () -> invokeSubAgent(sub, task, principal, viewer,
                            collector, variables, depth, visited, usage));
                } catch (Exception e) {
                    status = "ERROR";
                    output = "子 Agent 执行失败：" + rootMessage(e);
                }
                String modelOutput = limitModelOutput(output);
                long end = System.currentTimeMillis();
                collector.add(new ToolCallEvent(name, sub.getName(), "SUB_AGENT",
                        sub.getId(), sub.getName(), status, end - start,
                        truncate(toolInput, 4000), truncate(output, MAX_TOOL_EVENT_OUTPUT), usage[0], usage[1],
                        start, end));
                return modelOutput;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private String invokeSubAgent(AgentRegistry sub, String task, JwtPrincipal principal,
                                  KnowledgePermissionService.Viewer viewer, ToolCallCollector collector,
                                  Map<String, Object> variables, int depth, Set<Long> visited,
                                  long[] usage) {
        Map<String, Object> cfg = latestConfig(sub);
        String prompt = applyVariables(str(cfg.get("systemPrompt")), cfg.get("variables"), variables);
        Set<Long> nextVisited = new HashSet<>(visited);
        nextVisited.add(sub.getId());
        RuntimeTools subTools = build(sub.getId(), cfg, principal, viewer, collector, variables,
                depth, nextVisited);
        if (subTools.skillPrompt() != null) {
            prompt = prompt == null || prompt.isBlank() ? subTools.skillPrompt()
                    : prompt + subTools.skillPrompt();
        }
        List<Map<String, Object>> kbBindings = knowledgeBindings(sub.getId());
        for (Long kbId : subTools.knowledgeBaseIds()) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("knowledgeBaseId", kbId);
            b.put("enabled", true);
            kbBindings.add(b);
        }
        prompt = augmentWithKnowledge(prompt, task, kbBindings, viewer);

        Map<String, Object> modelConfig = asMap(cfg.get("modelConfig"));
        String modelName = str(modelConfig.getOrDefault("modelName", "deepseek-flash"));
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(modelName);
        if (modelConfig.get("temperature") instanceof Number n) options.temperature(n.doubleValue());
        prompt = contextWindow.fitPrompt(modelName, prompt, task);
        if (!subTools.callbacks().isEmpty()) options.toolCallbacks(subTools.callbacks());

        var spec = ChatClient.create(chatModelRouter.resolve(modelName)).prompt().options(options.build());
        if (prompt != null && !prompt.isBlank()) spec = spec.system(prompt);
        if (!subTools.callbacks().isEmpty()) spec = spec.toolCallbacks(subTools.callbacks());
        var resp = spec.user(task).call().chatResponse();
        String answer = resp.getResult() == null ? "" : resp.getResult().getOutput().getText();
        Usage u = resp.getMetadata() == null ? null : resp.getMetadata().getUsage();
        if (u != null) {
            usage[0] = u.getPromptTokens();
            usage[1] = u.getCompletionTokens();
        }
        return answer == null ? "" : answer;
    }

    private Map<String, Object> latestConfig(AgentRegistry agent) {
        AgentVersion v = versionMapper.selectList(new LambdaQueryWrapper<AgentVersion>()
                        .eq(AgentVersion::getAgentId, agent.getId())
                        .orderByDesc(AgentVersion::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (v != null && v.getSnapshot() != null) {
            Map<String, Object> snap = fromJsonMap(v.getSnapshot());
            if (snap.get("systemPrompt") != null) return snap;
        }
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("systemPrompt", agent.getSystemPrompt());
        cfg.put("variables", fromJsonValue(agent.getVariables()));
        cfg.put("modelConfig", fromJsonMap(agent.getModelConfig()));
        cfg.put("agentCapabilities", fromJsonMap(agent.getAgentCapabilities()));
        return cfg;
    }

    private boolean usable(AgentRegistry agent, JwtPrincipal principal) {
        if (!"PRIVATE".equals(agent.getVisibility())) return true;
        if (agent.getOwnerId().equals(principal.userId())) return true;
        if (principal.roles().stream().anyMatch(r -> r.equals("ADMIN") || r.equals("OWNER"))) return true;
        return grantMapper.selectCount(new LambdaQueryWrapper<AgentUserGrant>()
                .eq(AgentUserGrant::getAgentId, agent.getId())
                .eq(AgentUserGrant::getUserId, principal.userId())) > 0;
    }

    // ---------- 知识库（子 Agent 用） ----------

    private List<Map<String, Object>> knowledgeBindings(Long agentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentKnowledgeBase row : agentKbMapper.selectList(new LambdaQueryWrapper<AgentKnowledgeBase>()
                .eq(AgentKnowledgeBase::getAgentId, agentId)
                .eq(AgentKnowledgeBase::getEnabled, 1))) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("knowledgeBaseId", row.getKnowledgeBaseId());
            b.put("enabled", true);
            b.put("retrievalConfig", fromJsonMap(row.getRetrievalConfig()));
            result.add(b);
        }
        return result;
    }

    private String augmentWithKnowledge(String prompt, String query, List<Map<String, Object>> bindings,
                                        KnowledgePermissionService.Viewer viewer) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null || bindings.isEmpty()) return prompt;
        StringBuilder ctx = new StringBuilder();
        for (Map<String, Object> binding : bindings) {
            Long kbId = toLong(binding.get("knowledgeBaseId"));
            if (kbId == null || Boolean.FALSE.equals(binding.get("enabled"))) continue;
            if (permissionService.findVisible(kbId, viewer) == null) continue;
            Map<String, Object> rc = asMap(binding.get("retrievalConfig"));
            int topK = toInt(rc.get("topK"), 5);
            double threshold = toDouble(rc.get("scoreThreshold"), 0.5);
            try {
                for (Map<String, Object> hit : hybridRetrieval.search(tenantId, query, kbId, topK, threshold).hits()) {
                    ctx.append("\n---\n").append(hit.get("content"));
                }
            } catch (Exception e) {
                log.warn("子 Agent 知识库检索失败 kb={}：{}", kbId, e.getMessage());
            }
        }
        if (ctx.isEmpty()) return prompt;
        String rag = "以下是知识库检索到的参考资料，回答时优先依据这些内容：\n" + ctx;
        return prompt == null || prompt.isBlank() ? rag : prompt + "\n\n" + rag;
    }

    // ---------- 辅助 ----------

    private String schemaOf(ToolRegistry tool) {
        Map<String, Object> cfg = fromJsonMap(tool.getApiConfig());
        Object schema = cfg.get("inputSchema");
        if (schema != null) {
            try {
                return objectMapper.writeValueAsString(schema);
            } catch (Exception ignored) { }
        }
        // API 工具：由参数定义生成 inputSchema
        if (cfg.get("params") instanceof List<?> params && !params.isEmpty()) {
            Map<String, Object> props = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (Object o : params) {
                Map<String, Object> p = asMap(o);
                String name = str(p.get("name"));
                if (name == null) continue;
                Map<String, Object> prop = new LinkedHashMap<>();
                prop.put("type", p.getOrDefault("type", "string"));
                if (p.get("description") != null) prop.put("description", p.get("description"));
                props.put(name, prop);
                if (Boolean.TRUE.equals(p.get("required"))) required.add(name);
            }
            Map<String, Object> schemaMap = new LinkedHashMap<>();
            schemaMap.put("type", "object");
            schemaMap.put("properties", props);
            if (!required.isEmpty()) schemaMap.put("required", required);
            try {
                return objectMapper.writeValueAsString(schemaMap);
            } catch (Exception ignored) { }
        }
        return EMPTY_SCHEMA;
    }

    private Map<String, Object> args(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(toolInput, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("工具参数不是合法 JSON");
        }
    }

    private String substitute(String template, Map<String, Object> args) {
        if (template == null) return null;
        String result = template;
        for (Map.Entry<String, Object> e : args.entrySet()) {
            result = result.replace("{{" + e.getKey() + "}}", String.valueOf(e.getValue()));
        }
        return result;
    }

    private String substitutePath(String template, Map<String, Object> args) {
        String result = template;
        for (Map.Entry<String, Object> e : args.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", URLEncoder.encode(
                    String.valueOf(e.getValue()), StandardCharsets.UTF_8));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private String applyVariables(String text, Object varDefs, Map<String, Object> values) {
        if (text == null || !(varDefs instanceof List<?> defs)) return text;
        for (Object o : defs) {
            Map<String, Object> d = asMap(o);
            String key = str(d.get("key"));
            if (key == null || key.isBlank()) continue;
            Object v = values != null && values.get(key) != null ? values.get(key) : d.get("defaultValue");
            if (v != null) text = text.replace("{{" + key + "}}", String.valueOf(v));
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        if (o == null) return Map.of();
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        if (o instanceof String s) {
            try {
                return objectMapper.readValue(s, Map.class);
            } catch (Exception ignored) { }
        }
        return Map.of();
    }

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

    private Long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private int toInt(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private double toDouble(Object o, double def) {
        return o instanceof Number n ? n.doubleValue() : def;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 工具返回给模型的长度上限（超出截断并标注原长度，避免撑爆上下文）。 */
    private String limitModelOutput(String output) {
        int limit = Math.max(maxToolOutput, 500);
        if (output == null || output.length() <= limit) return output;
        return output.substring(0, limit) + "\n...[工具输出过长，已截断，原长度 " + output.length() + " 字符]";
    }

    private String truncate(String text, int limit) {
        if (text == null) return null;
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    /** 安全表达式解析（递归下降）：+ - * / % ^、括号、一元负号、sqrt/abs/min/max/pow/round/floor/ceil、pi/e。 */
    static final class Calculator {
        private final String s;
        private int pos;

        private Calculator(String s) {
            this.s = s == null ? "" : s;
        }

        static double eval(String expression) {
            if (expression == null || expression.isBlank()) throw new IllegalArgumentException("表达式为空");
            if (expression.length() > 200) throw new IllegalArgumentException("表达式过长");
            Calculator c = new Calculator(expression.replaceAll("\\s+", ""));
            double v = c.expr();
            if (c.pos != c.s.length()) throw new IllegalArgumentException("无法解析表达式");
            if (Double.isNaN(v) || Double.isInfinite(v)) throw new IllegalArgumentException("结果非法");
            return v;
        }

        private double expr() {
            double v = term();
            while (pos < s.length()) {
                char ch = s.charAt(pos);
                if (ch == '+') { pos++; v += term(); }
                else if (ch == '-') { pos++; v -= term(); }
                else break;
            }
            return v;
        }

        private double term() {
            double v = power();
            while (pos < s.length()) {
                char ch = s.charAt(pos);
                if (ch == '*') { pos++; v *= power(); }
                else if (ch == '/') {
                    pos++;
                    double d = power();
                    if (d == 0) throw new IllegalArgumentException("除数不能为 0");
                    v /= d;
                } else if (ch == '%') {
                    pos++;
                    v %= power();
                } else break;
            }
            return v;
        }

        private double power() {
            double v = unary();
            if (pos < s.length() && s.charAt(pos) == '^') { pos++; v = Math.pow(v, power()); }
            return v;
        }

        private double unary() {
            if (pos < s.length() && s.charAt(pos) == '-') { pos++; return -unary(); }
            if (pos < s.length() && s.charAt(pos) == '+') { pos++; return unary(); }
            return primary();
        }

        private double primary() {
            if (pos < s.length() && s.charAt(pos) == '(') {
                pos++;
                double v = expr();
                expect(')');
                return v;
            }
            if (pos < s.length() && Character.isDigit(s.charAt(pos)) || (pos < s.length() && s.charAt(pos) == '.')) {
                int start = pos;
                while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
                return Double.parseDouble(s.substring(start, pos));
            }
            if (pos < s.length() && Character.isLetter(s.charAt(pos))) {
                int start = pos;
                while (pos < s.length() && Character.isLetterOrDigit(s.charAt(pos))) pos++;
                String id = s.substring(start, pos).toLowerCase(Locale.ROOT);
                if ("pi".equals(id)) return Math.PI;
                if ("e".equals(id)) return Math.E;
                if (pos < s.length() && s.charAt(pos) == '(') {
                    pos++;
                    List<Double> args = new ArrayList<>();
                    if (pos < s.length() && s.charAt(pos) != ')') {
                        args.add(expr());
                        while (pos < s.length() && s.charAt(pos) == ',') {
                            pos++;
                            args.add(expr());
                        }
                    }
                    expect(')');
                    return fn(id, args);
                }
                throw new IllegalArgumentException("未知标识符：" + id);
            }
            throw new IllegalArgumentException("表达式不完整");
        }

        private void expect(char ch) {
            if (pos >= s.length() || s.charAt(pos) != ch) throw new IllegalArgumentException("缺少 " + ch);
            pos++;
        }

        private double fn(String id, List<Double> a) {
            return switch (id) {
                case "sqrt" -> Math.sqrt(one(a, id));
                case "abs" -> Math.abs(one(a, id));
                case "round" -> Math.round(one(a, id));
                case "floor" -> Math.floor(one(a, id));
                case "ceil" -> Math.ceil(one(a, id));
                case "pow" -> Math.pow(two(a, id)[0], two(a, id)[1]);
                case "min" -> Math.min(two(a, id)[0], two(a, id)[1]);
                case "max" -> Math.max(two(a, id)[0], two(a, id)[1]);
                default -> throw new IllegalArgumentException("未知函数：" + id);
            };
        }

        private double one(List<Double> a, String id) {
            if (a.size() != 1) throw new IllegalArgumentException(id + " 需要 1 个参数");
            return a.get(0);
        }

        private double[] two(List<Double> a, String id) {
            if (a.size() != 2) throw new IllegalArgumentException(id + " 需要 2 个参数");
            return new double[]{a.get(0), a.get(1)};
        }
    }
}
