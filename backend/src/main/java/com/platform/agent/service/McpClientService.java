package com.platform.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.agent.entity.McpServer;
import com.platform.agent.mapper.McpServerMapper;
import com.platform.common.security.CryptoService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * MCP 客户端（需求 3.15 / P1+）：
 * - HTTP（Streamable HTTP，含 Mcp-Session-Id 会话复用/过期重建）
 * - SSE（旧版事件流）
 * - STDIO（本地子进程，进程池 + 空闲回收 + 崩溃自动重启；命令白名单式校验，参数/环境变量隔离）
 * 协议版本 2025-03-26：structuredContent/outputSchema、tool annotations、progressToken/取消通知。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpClientService {

    private static final String PROTOCOL_VERSION = "2025-03-26";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
    private static final int READ_RETRIES = 2;
    private static final Duration HTTP_SESSION_TTL = Duration.ofMinutes(10);
    private static final Duration STDIO_IDLE = Duration.ofMinutes(10);
    private static final int MAX_LIST_PAGES = 20;
    private static final int MAX_LIST_ITEMS = 2000;
    private static final int MAX_STDIO_ARGS = 64;
    private static final int MAX_ARG_LENGTH = 4096;
    private static final int MAX_ENV_VALUE = 8192;
    private static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern COMMAND_ILLEGAL = Pattern.compile("[;&|`$><\"'(){}\\\\ \\n\\r\\t]");
    private static final Set<String> FORBIDDEN_COMMANDS = Set.of(
            "sh", "bash", "zsh", "dash", "ksh", "csh", "tcsh", "fish",
            "cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh", "pwsh.exe");
    private static final List<String> INHERIT_ENV = List.of(
            "PATH", "HOME", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "TMPDIR", "TEMP", "TMP",
            "USER", "LOGNAME", "SHELL", "SYSTEMROOT");

    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;

    /** 类级单例：HttpClient 线程安全且持有 selector 线程/连接池，不能每请求创建（无法关闭会泄漏）。 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final McpOAuthService oauthService;
    private final McpServerMapper serverMapper;

    /** 单次 resources/read 返回的总字节上限，防止恶意/异常大资源耗尽内存。 */
    @Value("${mcp.resource.max-bytes:1048576}")
    private int maxResourceBytes;

    private final Map<Long, CachedHttpSession> httpSessions = new ConcurrentHashMap<>();
    /** 服务端主动通知过 tools/list_changed 的本地标记；用于提示重新同步，不自动改已同步工具。 */
    private final Map<Long, Long> toolsChangedAt = new ConcurrentHashMap<>();
    private final Map<Long, StdioSession> stdioSessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mcp-session-maintenance");
        t.setDaemon(true);
        return t;
    });

    public record McpTool(String name, String description, String inputSchema,
                          String outputSchema, String annotations) {
        public McpTool(String name, String description, String inputSchema) {
            this(name, description, inputSchema, null, null);
        }
    }

    public record McpCallResult(String text, boolean error, String structuredContent) {
        public McpCallResult(String text, boolean error) {
            this(text, error, null);
        }
    }

    public record McpResource(String uri, String name, String description, String mimeType, Long size) {
    }

    public record McpResourceTemplate(String uriTemplate, String name, String description, String mimeType) {
    }

    public record McpResourceContent(String uri, String mimeType, String text, String blob) {
    }

    public record McpPrompt(String name, String description, String argumentsJson) {
    }

    public record McpPromptResult(String description, String messagesJson) {
    }

    private record CachedHttpSession(String sessionId, long expiresAt) {
    }

    @PostConstruct
    void init() {
        maintenance.scheduleWithFixedDelay(this::cleanup, 60, 60, TimeUnit.SECONDS);
    }

    public void markToolsChanged(Long serverId) {
        if (serverId == null) return;
        try {
            serverMapper.markToolsChanged(serverId);
            log.info("MCP 工具列表已变更 server={}（等待管理员重新同步）", serverId);
        } catch (Exception e) {
            // 通知处理不能影响当前 MCP 调用
            log.warn("MCP toolsChangedAt 持久化失败 server={}：{}", serverId, e.getMessage());
        }
    }

    public void markToolsSynced(Long serverId) {
        if (serverId == null) return;
        try {
            serverMapper.clearToolsChanged(serverId);
        } catch (Exception e) {
            log.warn("MCP toolsChangedAt 清理失败 server={}：{}", serverId, e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        maintenance.shutdownNow();
        stdioSessions.values().forEach(StdioSession::close);
        stdioSessions.clear();
    }

    // ---------- 对外入口 ----------

    public List<McpTool> listTools(McpServer server) {
        List<McpTool> all = new ArrayList<>();
        String cursor = null;
        Set<String> seen = new java.util.HashSet<>();
        for (int page = 0; page < MAX_LIST_PAGES; page++) {
            ObjectNode params = objectMapper.createObjectNode();
            if (cursor != null) params.put("cursor", cursor);
            JsonNode result = rpcWithRetry(server, "tools/list", params, TIMEOUT);
            parseTools(result, all);
            cursor = nextCursor(result);
            if (cursor == null) return all;
            if (!seen.add(cursor)) throw new IllegalStateException("MCP tools/list 返回重复 cursor：" + cursor);
        }
        throw new IllegalStateException("MCP tools/list 分页超过 " + MAX_LIST_PAGES + " 页");
    }

    /** MCP resources/list（无资源能力的 Server 会返回 method not found，由调用方降级为空）。 */
    public List<McpResource> listResources(McpServer server) {
        List<McpResource> rows = new ArrayList<>();
        String cursor = null;
        Set<String> seen = new java.util.HashSet<>();
        for (int page = 0; page < MAX_LIST_PAGES; page++) {
            ObjectNode params = objectMapper.createObjectNode();
            if (cursor != null) params.put("cursor", cursor);
            JsonNode result = rpcSafe(server, "resources/list", params, TIMEOUT, () -> false);
            JsonNode arr = result.path("resources");
            if (arr.isArray()) {
                for (JsonNode r : arr) {
                    String uri = r.path("uri").asText("");
                    if (uri.isBlank()) continue;
                    rows.add(new McpResource(uri, r.path("name").asText(uri),
                            r.path("description").asText(""), r.path("mimeType").asText(null),
                            r.has("size") && r.get("size").canConvertToLong() ? r.get("size").asLong() : null));
                    if (rows.size() > MAX_LIST_ITEMS) throw new IllegalStateException("MCP resources 超过 " + MAX_LIST_ITEMS + " 条");
                }
            }
            cursor = nextCursor(result);
            if (cursor == null) return rows;
            if (!seen.add(cursor)) throw new IllegalStateException("MCP resources/list 返回重复 cursor：" + cursor);
        }
        throw new IllegalStateException("MCP resources/list 分页超过 " + MAX_LIST_PAGES + " 页");
    }

    public List<McpResourceTemplate> listResourceTemplates(McpServer server) {
        List<McpResourceTemplate> rows = new ArrayList<>();
        String cursor = null;
        Set<String> seen = new java.util.HashSet<>();
        for (int page = 0; page < MAX_LIST_PAGES; page++) {
            ObjectNode params = objectMapper.createObjectNode();
            if (cursor != null) params.put("cursor", cursor);
            JsonNode result = rpcSafe(server, "resources/templates/list", params, TIMEOUT, () -> false);
            JsonNode arr = result.path("resourceTemplates");
            if (arr.isArray()) {
                for (JsonNode r : arr) {
                    String uri = r.path("uriTemplate").asText("");
                    if (uri.isBlank()) continue;
                    rows.add(new McpResourceTemplate(uri, r.path("name").asText(uri),
                            r.path("description").asText(""), r.path("mimeType").asText(null)));
                    if (rows.size() > MAX_LIST_ITEMS) throw new IllegalStateException("MCP resourceTemplates 超过 " + MAX_LIST_ITEMS + " 条");
                }
            }
            cursor = nextCursor(result);
            if (cursor == null) return rows;
            if (!seen.add(cursor)) throw new IllegalStateException("MCP templates/list 返回重复 cursor：" + cursor);
        }
        throw new IllegalStateException("MCP templates/list 分页超过 " + MAX_LIST_PAGES + " 页");
    }

    public List<McpResourceContent> readResource(McpServer server, String uri) {
        JsonNode result = rpcSafe(server, "resources/read", Map.of("uri", uri), CALL_TIMEOUT, () -> false);
        List<McpResourceContent> rows = new ArrayList<>();
        JsonNode arr = result.path("contents");
        if (arr.isArray()) {
            for (JsonNode c : arr) {
                rows.add(new McpResourceContent(c.path("uri").asText(uri),
                        c.path("mimeType").asText(null), c.path("text").asText(null),
                        c.path("blob").asText(null)));
            }
        }
        checkResourceSize(rows);
        return rows;
    }

    public List<McpPrompt> listPrompts(McpServer server) {
        List<McpPrompt> rows = new ArrayList<>();
        String cursor = null;
        Set<String> seen = new java.util.HashSet<>();
        for (int page = 0; page < MAX_LIST_PAGES; page++) {
            ObjectNode params = objectMapper.createObjectNode();
            if (cursor != null) params.put("cursor", cursor);
            JsonNode result = rpcSafe(server, "prompts/list", params, TIMEOUT, () -> false);
            JsonNode arr = result.path("prompts");
            if (arr.isArray()) {
                for (JsonNode p : arr) {
                    String name = p.path("name").asText("");
                    if (name.isBlank()) continue;
                    JsonNode args = p.get("arguments");
                    rows.add(new McpPrompt(name, p.path("description").asText(""),
                            args == null ? null : args.toString()));
                    if (rows.size() > MAX_LIST_ITEMS) throw new IllegalStateException("MCP prompts 超过 " + MAX_LIST_ITEMS + " 条");
                }
            }
            cursor = nextCursor(result);
            if (cursor == null) return rows;
            if (!seen.add(cursor)) throw new IllegalStateException("MCP prompts/list 返回重复 cursor：" + cursor);
        }
        throw new IllegalStateException("MCP prompts/list 分页超过 " + MAX_LIST_PAGES + " 页");
    }

    public McpPromptResult getPrompt(McpServer server, String name, Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        if (arguments != null) params.put("arguments", arguments);
        JsonNode result = rpcSafe(server, "prompts/get", params, CALL_TIMEOUT, () -> false);
        JsonNode messages = result.get("messages");
        String messagesJson = messages == null ? null : messages.toString();
        int limit = Math.max(1024, maxResourceBytes);
        if (messagesJson != null && messagesJson.length() > limit) {
            throw new IllegalStateException("MCP prompt 结果超过上限 " + limit + " 字符");
        }
        return new McpPromptResult(result.path("description").asText(null), messagesJson);
    }

    public McpCallResult callTool(McpServer server, String toolName, Map<String, Object> arguments) {
        return callTool(server, toolName, arguments, () -> false);
    }

    /** cancelled 由对话停止信号驱动：HTTP 取消本地请求；stdio 直接终止子进程。 */
    public McpCallResult callTool(McpServer server, String toolName, Map<String, Object> arguments,
                                  BooleanSupplier cancelled) {
        return callTool(server, toolName, arguments, cancelled, null);
    }

    /** 工作流恢复时可传幂等键，MCP Server 可将其用于上游去重。 */
    public McpCallResult callTool(McpServer server, String toolName, Map<String, Object> arguments,
                                  BooleanSupplier cancelled, String idempotencyKey) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments == null ? Map.of() : arguments);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("progressToken", "p-" + UUID.randomUUID());
        if (idempotencyKey != null && !idempotencyKey.isBlank()) meta.put("idempotencyKey", idempotencyKey);
        params.put("_meta", meta);
        Exception last = null;
        // 写操作只重试一次，避免重复副作用
        for (int attempt = 0; attempt <= 1; attempt++) {
            try {
                if (cancelled.getAsBoolean()) throw new IllegalStateException("MCP 调用已取消");
                JsonNode result = rpc(server, "tools/call", params, CALL_TIMEOUT, cancelled);
                if (cancelled.getAsBoolean()) throw new IllegalStateException("MCP 调用已取消");
                return parseCallResult(result);
            } catch (Exception e) {
                if (cancelled.getAsBoolean()) throw new IllegalStateException("MCP 调用已取消");
                last = e;
                log.warn("MCP tools/call 失败（第 {} 次）server={} tool={}：{}",
                        attempt + 1, server.getId(), toolName, rootMessage(e));
                backoff(attempt);
            }
        }
        throw new IllegalStateException(rootMessage(last));
    }

    private JsonNode rpcSafe(McpServer server, String method, Object params, Duration timeout,
                             BooleanSupplier cancelled) {
        try {
            return rpc(server, method, params, timeout, cancelled);
        } catch (Exception e) {
            throw new IllegalStateException(rootMessage(e));
        }
    }

    private JsonNode rpc(McpServer server, String method, Object params, Duration timeout,
                         BooleanSupplier cancelled) throws Exception {
        if (cancelled.getAsBoolean()) throw new IllegalStateException("MCP 调用已取消");
        String transport = server.getTransport() == null ? "HTTP" : server.getTransport().toUpperCase(Locale.ROOT);
        return switch (transport) {
            case "STDIO" -> stdioSession(server).rpc(method, params, timeout, cancelled);
            case "SSE" -> rpcSse(server, method, params, cancelled);
            default -> rpcHttp(server, method, params, cancelled);
        };
    }

    // ---------- HTTP 传输 ----------

    private JsonNode rpcHttp(McpServer server, String method, Object params,
                             BooleanSupplier cancelled) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (cancelled.getAsBoolean()) throw new IllegalStateException("MCP 调用已取消");
            String sessionId = httpSessionId(server, cancelled);
            try {
                JsonNode node = rpcHttpWithSession(server, sessionId, method,
                        params instanceof JsonNode n ? n : objectMapper.valueToTree(params), 2, cancelled);
                touchHttpSession(server.getId());
                return node;
            } catch (SessionExpiredException e) {
                last = e;
                log.info("MCP HTTP 会话失效，重新 initialize：server={}", server.getId());
                invalidateHttpSession(server.getId());
            } catch (UnauthorizedException e) {
                last = e;
                boolean oauth = "OAUTH".equalsIgnoreCase(server.getAuthType());
                boolean feishu = "FEISHU_APP".equalsIgnoreCase(server.getAuthType());
                if (!oauth && !feishu) throw e;
                if (cancelled.getAsBoolean()) throw new IllegalStateException("MCP 调用已取消");
                if (feishu) {
                    log.info("MCP 飞书 401，刷新 tenant_access_token 后重试：server={}", server.getId());
                    clearFeishuToken(server);
                } else {
                    log.info("MCP OAuth 401，强制刷新 token 后重试：server={}", server.getId());
                    oauthService.forceRefresh(server);
                }
                invalidateHttpSession(server.getId());
            }
        }
        throw last;
    }

    private String httpSessionId(McpServer server, BooleanSupplier cancelled) throws Exception {
        CachedHttpSession cached = httpSessions.get(server.getId());
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            return cached.sessionId();
        }
        return initializeHttp(server, cancelled);
    }

    private String initializeHttp(McpServer server, BooleanSupplier cancelled) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "ai-agent-platform", "version", "1.0"));
        HttpResponse<String> resp = postHttp(server, Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "initialize", "params", params), null, cancelled);
        String sessionId = resp.headers().firstValue("Mcp-Session-Id").orElse(null);
        JsonNode node = parseHttpResponse(resp);
        if (node.has("error")) throw new IllegalStateException("initialize 失败：" + node.path("error").path("message").asText());
        try {
            postHttp(server, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), sessionId, cancelled);
        } catch (Exception e) {
            log.debug("MCP initialized 通知失败（忽略）：{}", e.getMessage());
        }
        if (sessionId != null && !sessionId.isBlank()) {
            httpSessions.put(server.getId(), new CachedHttpSession(sessionId,
                    System.currentTimeMillis() + HTTP_SESSION_TTL.toMillis()));
        }
        return sessionId;
    }

    private void touchHttpSession(Long serverId) {
        CachedHttpSession cached = httpSessions.get(serverId);
        if (cached != null) {
            httpSessions.put(serverId, new CachedHttpSession(cached.sessionId(),
                    System.currentTimeMillis() + HTTP_SESSION_TTL.toMillis()));
        }
    }

    private void invalidateHttpSession(Long serverId) {
        httpSessions.remove(serverId);
    }

    private JsonNode rpcHttpWithSession(McpServer server, String sessionId, String method,
                                        Object params, int id, BooleanSupplier cancelled) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("method", method);
        body.put("params", params);
        HttpResponse<String> resp = postHttp(server, body, sessionId, cancelled);
        inspectHttpNotifications(server, resp);
        JsonNode node = parseHttpResponse(resp);
        if (node.has("error")) throw new IllegalStateException(method + " 失败：" + node.path("error").path("message").asText());
        return node.path("result");
    }

    private HttpResponse<String> postHttp(McpServer server, Object body, String sessionId,
                                           BooleanSupplier cancelled) throws Exception {
        HttpRequest.Builder builder = requestBuilder(server)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (sessionId != null && !sessionId.isBlank()) builder.header("Mcp-Session-Id", sessionId);
        java.util.concurrent.CompletableFuture<HttpResponse<String>> future =
                httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> resp;
        while (true) {
            if (cancelled.getAsBoolean()) {
                future.cancel(true);
                throw new IllegalStateException("MCP 调用已取消");
            }
            try {
                resp = future.get(200, TimeUnit.MILLISECONDS);
                break;
            } catch (TimeoutException te) {
                // 轮询取消信号；请求本身的 timeout 由 HttpRequest 控制
            }
        }
        if (resp.statusCode() == 401) {
            throw new UnauthorizedException("HTTP 401：" + truncate(respBody(resp), 200));
        }
        if (resp.statusCode() == 404) {
            throw new SessionExpiredException("HTTP 404（会话可能已过期）");
        }
        String respBody = resp.body() == null ? "" : resp.body();
        if (resp.statusCode() == 400 && respBody.toLowerCase(Locale.ROOT).contains("session")) {
            throw new SessionExpiredException("HTTP 400 会话失效：" + truncate(respBody, 200));
        }
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + "：" + truncate(respBody, 300));
        }
        return resp;
    }

    private void inspectHttpNotifications(McpServer server, HttpResponse<String> resp) {
        String body = resp.body();
        if (body == null || !body.contains("notifications/tools/list_changed")) return;
        markToolsChanged(server.getId());
    }

    private JsonNode parseHttpResponse(HttpResponse<String> resp) throws Exception {
        String body = resp.body();
        if (body == null || body.isBlank()) throw new IllegalStateException("MCP 返回为空");
        String contentType = resp.headers().firstValue("content-type").orElse("");
        if (contentType.contains("text/event-stream")) {
            JsonNode last = null;
            for (String line : body.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("data:")) continue;
                String json = trimmed.substring(5).trim();
                if (json.isEmpty() || "[DONE]".equals(json)) continue;
                try {
                    last = objectMapper.readTree(json);
                } catch (Exception e) {     log.debug("MCP 忽略异常：{}", e.getMessage()); }
            }
            if (last == null) throw new IllegalStateException("SSE 响应中没有 JSON-RPC 数据");
            return last;
        }
        return objectMapper.readTree(body);
    }

    private static final class SessionExpiredException extends Exception {
        SessionExpiredException(String message) {
            super(message);
        }
    }

    private static final class UnauthorizedException extends Exception {
        UnauthorizedException(String message) {
            super(message);
        }
    }

    private String respBody(HttpResponse<String> resp) {
        return resp.body() == null ? "" : resp.body();
    }

    // ---------- SSE 传输 ----------

    private JsonNode rpcSse(McpServer server, String method, Object params,
                            BooleanSupplier cancelled) throws Exception {
        try (SseSession session = SseSession.open(server, this)) {
            session.rpc(1, "initialize", Map.of(
                    "protocolVersion", PROTOCOL_VERSION,
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "ai-agent-platform", "version", "1.0")), cancelled);
            try {
                session.notify("notifications/initialized", Map.of());
            } catch (Exception e) {
                log.debug("MCP 忽略异常：{}", e.getMessage());
            }
            return session.rpc(2, method, params, cancelled);
        }
    }

    /** 旧版 MCP SSE：GET 事件流 → endpoint 事件（消息地址）→ POST JSON-RPC → 事件流返回响应。 */
    private static final class SseSession implements AutoCloseable {
        private final McpServer server;
        private final McpClientService owner;
        private final HttpClient client;
        private final HttpResponse<InputStream> response;
        private final BufferedReader reader;
        private final Thread readerThread;
        private final CompletableFuture<String> endpointFuture = new CompletableFuture<>();
        private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile String messageUrl;

        private SseSession(McpServer server, McpClientService owner, HttpClient client,
                           HttpResponse<InputStream> response) {
            this.server = server;
            this.owner = owner;
            this.client = client;
            this.response = response;
            this.reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
            this.readerThread = new Thread(this::readLoop, "mcp-sse-" + server.getId());
            this.readerThread.setDaemon(true);
        }

        static SseSession open(McpServer server, McpClientService owner) throws Exception {
            HttpClient client = owner.httpClient;   // 复用类级单例
            HttpRequest request = owner.requestBuilder(server)
                    .header("Accept", "text/event-stream")
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("SSE 连接失败：HTTP " + response.statusCode());
            }
            SseSession session = new SseSession(server, owner, client, response);
            session.readerThread.start();
            String endpoint = session.endpointFuture.get(10, TimeUnit.SECONDS);
            session.messageUrl = URI.create(server.getEndpoint()).resolve(endpoint).toString();
            return session;
        }

        private void readLoop() {
            String event = "message";
            StringBuilder data = new StringBuilder();
            try {
                String line;
                while (!closed.get() && (line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        dispatch(event, data.toString());
                        event = "message";
                        data.setLength(0);
                    } else if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        if (data.length() > 0) data.append('\n');
                        data.append(line.substring(5).trim());
                    }
                }
            } catch (Exception e) {
                if (!closed.get()) log.debug("MCP SSE 读取结束 server={}：{}", server.getId(), e.getMessage());
            } finally {
                endpointFuture.completeExceptionally(new IllegalStateException("SSE 连接关闭"));
                pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException("SSE 连接关闭")));
            }
        }

        private void dispatch(String event, String data) {
            if (data.isEmpty()) return;
            try {
                if ("endpoint".equals(event)) {
                    endpointFuture.complete(data);
                    return;
                }
                JsonNode node = owner.objectMapper.readTree(data);
                String method = node.path("method").asText("");
                if ("notifications/tools/list_changed".equals(method)) {
                    owner.markToolsChanged(server.getId());
                }
                int id = node.path("id").asInt(-1);
                CompletableFuture<JsonNode> future = pending.remove(id);
                if (future != null) future.complete(node);
            } catch (Exception e) {     log.debug("MCP 忽略异常：{}", e.getMessage()); }
        }

        JsonNode rpc(int id, String method, Object params, BooleanSupplier cancelled) throws Exception {
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            pending.put(id, future);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jsonrpc", "2.0");
            body.put("id", id);
            body.put("method", method);
            body.put("params", params);
            post(body);
            JsonNode node;
            try {
                while (true) {
                    if (cancelled.getAsBoolean()) {
                        try {
                            notify("notifications/cancelled", Map.of("requestId", id, "reason", "cancelled by user"));
                        } catch (Exception cancelError) {
                            log.debug("MCP 取消通知发送失败：{}", cancelError.getMessage());
                        }
                        throw new IllegalStateException("MCP 调用已取消");
                    }
                    try {
                        node = future.get(200, TimeUnit.MILLISECONDS);
                        break;
                    } catch (TimeoutException te) {
                        // 轮询取消信号
                    }
                }
            } finally {
                pending.remove(id);
            }
            if (node.has("error")) {
                throw new IllegalStateException(method + " 失败：" + node.path("error").path("message").asText());
            }
            return node.path("result");
        }

        void notify(String method, Object params) throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jsonrpc", "2.0");
            body.put("method", method);
            body.put("params", params);
            post(body);
        }

        private void post(Object body) throws Exception {
            if (messageUrl == null) throw new IllegalStateException("SSE 消息地址未就绪");
            HttpRequest request = owner.requestBuilder(server, URI.create(messageUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            owner.objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .timeout(TIMEOUT)
                    .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new IllegalStateException("SSE 消息发送失败：HTTP " + resp.statusCode());
            }
        }

        @Override
        public void close() {
            closed.set(true);
            try {
                response.body().close();
            } catch (Exception e) {     log.debug("MCP 忽略异常：{}", e.getMessage()); }
            try {
                reader.close();
            } catch (Exception e) {     log.debug("MCP 忽略异常：{}", e.getMessage()); }
            readerThread.interrupt();
        }
    }

    // ---------- STDIO 传输 ----------

    private StdioSession stdioSession(McpServer server) throws Exception {
        StdioSession existing = stdioSessions.get(server.getId());
        if (existing != null && existing.healthy() && existing.matches(server)) {
            existing.touch();
            return existing;
        }
        synchronized (stdioSessions) {
            existing = stdioSessions.get(server.getId());
            if (existing != null && existing.healthy() && existing.matches(server)) {
                existing.touch();
                return existing;
            }
            if (existing != null) existing.close();
            StdioSession created = StdioSession.open(server, this);
            stdioSessions.put(server.getId(), created);
            return created;
        }
    }

    /** 配置指纹：命令/参数/环境变量变化时重建子进程。 */
    String stdioFingerprint(McpServer server) {
        return (server.getCommand() == null ? "" : server.getCommand()) + '\u0000'
                + (server.getArgs() == null ? "" : server.getArgs()) + '\u0000'
                + (server.getEnvEncrypted() == null ? "" : server.getEnvEncrypted());
    }

    /** STDIO 会话：本地子进程 + 行分隔 JSON-RPC，长连接复用，空闲/崩溃自动回收。 */
    private static final class StdioSession {
        private final McpServer server;
        private final McpClientService owner;
        private final Process process;
        private final String fingerprint;
        private final BufferedWriter writer;
        private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private final AtomicInteger seq = new AtomicInteger(1);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Thread readerThread;
        private final Thread stderrThread;
        private volatile long lastUsedAt = System.currentTimeMillis();

        private StdioSession(McpServer server, McpClientService owner, Process process) {
            this.server = server;
            this.owner = owner;
            this.process = process;
            this.fingerprint = owner.stdioFingerprint(server);
            this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.readerThread = new Thread(this::readLoop, "mcp-stdio-" + server.getId());
            this.readerThread.setDaemon(true);
            this.stderrThread = new Thread(this::drainStderr, "mcp-stdio-err-" + server.getId());
            this.stderrThread.setDaemon(true);
        }

        static StdioSession open(McpServer server, McpClientService owner) throws Exception {
            owner.validateStdioConfig(server);
            List<String> command = new ArrayList<>();
            command.add(server.getCommand().trim());
            command.addAll(owner.stdioArgs(server));
            ProcessBuilder pb = new ProcessBuilder(command);
            Map<String, String> env = pb.environment();
            env.clear();
            for (String key : INHERIT_ENV) {
                String value = System.getenv(key);
                if (value != null) env.put(key, value);
            }
            env.putAll(owner.stdioEnv(server));
            // npx/uvx 的 shebang 依赖 PATH 找 node/python；后端常由精简环境启动
            // （手动 nohup / systemd），这里追加常见运行时目录兜底
            env.merge("PATH", owner.extraPathEntries(), (cur, extra) -> cur + ":" + extra);
            if (env.get("HOME") == null) {
                env.put("HOME", System.getProperty("user.home"));
            }
            pb.directory(owner.stdioWorkDir(server));
            Process process = pb.start();
            StdioSession session = new StdioSession(server, owner, process);
            session.readerThread.start();
            session.stderrThread.start();
            try {
                session.rpc("initialize", Map.of(
                        "protocolVersion", PROTOCOL_VERSION,
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "ai-agent-platform", "version", "1.0")), TIMEOUT, () -> false);
                session.notify("notifications/initialized", Map.of());
            } catch (Exception e) {
                session.close();
                throw new IllegalStateException("MCP stdio 初始化失败：" + owner.rootMessage(e));
            }
            log.info("MCP stdio 会话已建立 server={} command={}", server.getId(), server.getCommand());
            return session;
        }

        boolean healthy() {
            return !closed.get() && process.isAlive();
        }

        boolean matches(McpServer candidate) {
            return fingerprint.equals(owner.stdioFingerprint(candidate));
        }

        void touch() {
            lastUsedAt = System.currentTimeMillis();
        }

        long lastUsedAt() {
            return lastUsedAt;
        }

        JsonNode rpc(String method, Object params, Duration timeout,
                     BooleanSupplier cancelled) throws Exception {
            if (!healthy()) throw new IllegalStateException("MCP stdio 进程不可用（command=" + server.getCommand() + "）");
            int id = seq.getAndIncrement();
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            pending.put(id, future);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jsonrpc", "2.0");
            body.put("id", id);
            body.put("method", method);
            body.put("params", params);
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            try {
                send(body);
                touch();
                while (true) {
                    if (cancelled.getAsBoolean()) {
                        try {
                            notify("notifications/cancelled", Map.of("requestId", id, "reason", "cancelled by user"));
                        } catch (Exception cancelError) {
                            log.debug("MCP stdio 取消失败：{}", cancelError.getMessage());
                        }
                        close(); // 直接终止本地子进程，保证停止副作用
                        throw new IllegalStateException("MCP 调用已取消");
                    }
                    try {
                        JsonNode node = future.get(200, TimeUnit.MILLISECONDS);
                        if (node.has("error")) {
                            throw new IllegalStateException(method + " 失败："
                                    + node.path("error").path("message").asText());
                        }
                        return node.path("result");
                    } catch (TimeoutException te) {
                        if (System.currentTimeMillis() > deadline) {
                            throw te;
                        }
                    }
                }
            } catch (TimeoutException e) {
                // 协议 2025-03-26：超时后尽力发送取消通知
                try {
                    notify("notifications/cancelled", Map.of("requestId", id, "reason", "timeout"));
                } catch (Exception cancelError) {
                    log.debug("MCP 取消通知发送失败：{}", cancelError.getMessage());
                }
                throw new IllegalStateException(method + " 超时（" + timeout.toSeconds() + "s）");
            } finally {
                pending.remove(id);
            }
        }

        void notify(String method, Object params) throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jsonrpc", "2.0");
            body.put("method", method);
            body.put("params", params);
            send(body);
        }

        private void send(Object body) throws Exception {
            String line = owner.objectMapper.writeValueAsString(body);
            synchronized (writer) {
                writer.write(line);
                writer.write('\n');
                writer.flush();
            }
        }

        private void readLoop() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!closed.get() && (line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    JsonNode node;
                    try {
                        node = owner.objectMapper.readTree(line);
                    } catch (Exception e) {
                        log.debug("MCP stdio 非 JSON 输出（忽略）server={}：{}", server.getId(), owner.truncate(line, 200));
                        continue;
                    }
                    JsonNode idNode = node.get("id");
                    if (idNode != null && idNode.canConvertToInt()) {
                        CompletableFuture<JsonNode> future = pending.remove(idNode.asInt());
                        if (future != null) future.complete(node);
                    } else {
                        String method = node.path("method").asText("");
                        if (method.contains("progress")) {
                            log.debug("MCP stdio 进度 server={}：{}", server.getId(), node.path("params"));
                        } else if ("notifications/tools/list_changed".equals(method)) {
                            owner.markToolsChanged(server.getId());
                        } else if (!method.isBlank()) {
                            log.debug("MCP stdio 通知 server={} method={}", server.getId(), method);
                        }
                    }
                }
            } catch (Exception e) {
                if (!closed.get()) log.debug("MCP stdio 读取结束 server={}：{}", server.getId(), e.getMessage());
            } finally {
                failPending("MCP stdio 进程已退出");
            }
        }

        private void drainStderr() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!closed.get() && (line = reader.readLine()) != null) {
                    log.debug("MCP stdio[{}] stderr：{}", server.getId(), owner.truncate(line, 500));
                }
            } catch (Exception e) {     log.debug("MCP 忽略异常：{}", e.getMessage()); }
        }

        private void failPending(String reason) {
            pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException(reason)));
            pending.clear();
        }

        void close() {
            if (!closed.compareAndSet(false, true)) return;
            failPending("MCP stdio 会话已关闭");
            try {
                writer.close();
            } catch (Exception e) {     log.debug("MCP 忽略异常：{}", e.getMessage()); }
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            readerThread.interrupt();
            stderrThread.interrupt();
        }
    }

    private void validateStdioConfig(McpServer server) {
        String command = server.getCommand() == null ? "" : server.getCommand().trim();
        if (command.isEmpty()) throw new IllegalStateException("STDIO 需要配置启动命令");
        if (command.length() > 256) throw new IllegalStateException("启动命令过长");
        if (COMMAND_ILLEGAL.matcher(command).find()) {
            throw new IllegalStateException("启动命令包含非法字符（不允许 shell 特殊字符/空格）");
        }
        String base = command.substring(command.lastIndexOf('/') + 1)
                .substring(command.lastIndexOf('\\') + 1).toLowerCase(Locale.ROOT);
        if (FORBIDDEN_COMMANDS.contains(base)) {
            throw new IllegalStateException("禁止以 shell 解释器作为 MCP stdio 命令：" + base);
        }
    }

    List<String> stdioArgs(McpServer server) throws Exception {
        if (server.getArgs() == null || server.getArgs().isBlank()) return List.of();
        List<?> raw = objectMapper.readValue(server.getArgs(), List.class);
        List<String> args = new ArrayList<>();
        for (Object item : raw) {
            String arg = item == null ? "" : String.valueOf(item);
            if (arg.length() > MAX_ARG_LENGTH) throw new IllegalStateException("MCP stdio 参数过长");
            if (arg.contains("\n") || arg.contains("\r")) throw new IllegalStateException("MCP stdio 参数不允许换行");
            args.add(arg);
            if (args.size() > MAX_STDIO_ARGS) throw new IllegalStateException("MCP stdio 参数数量超过 " + MAX_STDIO_ARGS);
        }
        return args;
    }

    Map<String, String> stdioEnv(McpServer server) {
        if (server.getEnvEncrypted() == null || server.getEnvEncrypted().isBlank()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        try {
            Map<?, ?> raw = objectMapper.readValue(cryptoService.decrypt(server.getEnvEncrypted()), Map.class);
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (!ENV_KEY.matcher(key).matches()) continue;
                String value = e.getValue() == null ? "" : String.valueOf(e.getValue());
                if (value.length() > MAX_ENV_VALUE) throw new IllegalStateException("环境变量值过长：" + key);
                result.put(key, value);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("MCP stdio 环境变量解密失败：AES_KEY 可能已变更");
        }
        return result;
    }

    private java.io.File stdioWorkDir(McpServer server) {
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "ai-agent-mcp", "server-" + server.getId());
            Files.createDirectories(dir);
            return dir.toFile();
        } catch (Exception e) {
            return Path.of(System.getProperty("java.io.tmpdir")).toFile();
        }
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        stdioSessions.forEach((id, session) -> {
            if (!session.healthy() || now - session.lastUsedAt() > STDIO_IDLE.toMillis()) {
                session.close();
                stdioSessions.remove(id, session);
            }
        });
        httpSessions.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
    }

    /** Server 删除/配置变更时释放本地进程与 HTTP 会话缓存。 */
    public void closeServer(Long serverId) {
        StdioSession session = stdioSessions.remove(serverId);
        if (session != null) session.close();
        httpSessions.remove(serverId);
    }

    // ---------- 公共 ----------

    private HttpRequest.Builder requestBuilder(McpServer server) {
        return requestBuilder(server, URI.create(server.getEndpoint()));
    }

    private HttpRequest.Builder requestBuilder(McpServer server, URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(TIMEOUT);
        for (Map.Entry<String, Object> h : headersOf(server).entrySet()) {
            builder.header(h.getKey(), String.valueOf(h.getValue()));
        }
        applyAuth(builder, server);
        return builder;
    }

    private Map<String, Object> headersOf(McpServer server) {
        if (server.getCustomHeaders() == null || server.getCustomHeaders().isBlank()) return Map.of();
        try {
            return objectMapper.readValue(server.getCustomHeaders(), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String nextCursor(JsonNode result) {
        JsonNode cursor = result.get("nextCursor");
        if (cursor == null || cursor.isNull()) return null;
        String value = cursor.asText("");
        return value.isBlank() ? null : value;
    }

    private JsonNode rpcWithRetry(McpServer server, String method, Object params, Duration timeout) {
        Exception last = null;
        for (int attempt = 0; attempt <= READ_RETRIES; attempt++) {
            try {
                return rpc(server, method, params, timeout, () -> false);
            } catch (Exception e) {
                last = e;
                log.warn("MCP {} 失败（第 {} 次）server={}：{}", method, attempt + 1, server.getId(), rootMessage(e));
                backoff(attempt);
            }
        }
        throw new IllegalStateException(rootMessage(last));
    }

    private void checkResourceSize(List<McpResourceContent> contents) {
        long bytes = 0;
        for (McpResourceContent c : contents) {
            if (c.text() != null) bytes += c.text().getBytes(StandardCharsets.UTF_8).length;
            if (c.blob() != null) bytes += c.blob().length();
        }
        int limit = Math.max(1024, maxResourceBytes);
        if (bytes > limit) {
            throw new IllegalStateException("MCP resource 内容超过上限 " + limit + " bytes（实际 " + bytes + "）");
        }
    }

    private List<McpTool> parseTools(JsonNode result, List<McpTool> tools) {
        JsonNode arr = result.path("tools");
        if (arr.isArray()) {
            for (JsonNode t : arr) {
                String name = t.path("name").asText(null);
                if (name == null || name.isBlank()) continue;
                JsonNode schema = t.get("inputSchema");
                JsonNode outputSchema = t.get("outputSchema");
                JsonNode annotations = t.get("annotations");
                tools.add(new McpTool(name, t.path("description").asText(""),
                        schema == null ? null : schema.toString(),
                        outputSchema == null ? null : outputSchema.toString(),
                        annotations == null ? null : annotations.toString()));
                if (tools.size() > MAX_LIST_ITEMS) {
                    throw new IllegalStateException("MCP tools 超过 " + MAX_LIST_ITEMS + " 条");
                }
            }
        }
        return tools;
    }

    private McpCallResult parseCallResult(JsonNode result) {
        boolean error = result.path("isError").asBoolean(false);
        JsonNode structured = result.get("structuredContent");
        String structuredText = structured == null || structured.isNull() ? null : structured.toString();
        StringBuilder text = new StringBuilder();
        JsonNode content = result.path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                if ("text".equals(item.path("type").asText())) {
                    if (text.length() > 0) text.append('\n');
                    text.append(item.path("text").asText(""));
                } else {
                    if (text.length() > 0) text.append('\n');
                    text.append(item.toString());
                }
            }
        }
        if (text.length() == 0 && structuredText != null) {
            text.append(structuredText);
        }
        if (text.length() == 0) text.append(result.toString());
        return new McpCallResult(text.toString(), error, structuredText);
    }

    private void applyAuth(HttpRequest.Builder builder, McpServer server) {
        String type = server.getAuthType() == null ? "NONE" : server.getAuthType().toUpperCase();
        if ("NONE".equals(type)) return;
        Map<String, Object> auth = authOf(server);
        switch (type) {
            case "API_KEY" -> builder.header(
                    str(auth.getOrDefault("header", "X-API-Key")), str(auth.get("value")));
            case "OAUTH" -> builder.header("Authorization", "Bearer " + oauthService.accessToken(server));
            case "BEARER" -> builder.header("Authorization", "Bearer " + str(auth.get("token")));
            case "BASIC" -> {
                String raw = str(auth.getOrDefault("username", "")) + ":" + str(auth.getOrDefault("password", ""));
                builder.header("Authorization", "Basic "
                        + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
            }
            default -> { }
        }
    }

    /**
     * 飞书自建应用 tenant_access_token（app_id/app_secret → 2h token）。
     * Redis 缓存至过期前 5 分钟；401 时由调用方 clearFeishuToken 强制刷新。
     */
    private String feishuTenantToken(McpServer server) {
        String cacheKey = "mcp:feishu-token:" + server.getId();
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null && !cached.isBlank()) return cached;
        } catch (Exception e) {
            log.debug("飞书 token 缓存读取失败：{}", e.getMessage());
        }
        Map<String, Object> auth = authOf(server);
        String appId = str(auth.get("appId"));
        String appSecret = str(auth.get("appSecret"));
        if (appId.isBlank() || appSecret.isBlank()) {
            throw new IllegalStateException("飞书应用凭证缺失（appId/appSecret）");
        }
        try {
            HttpResponse<String> resp = httpClient.send(
                    HttpRequest.newBuilder(URI.create("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"))
                            .header("Content-Type", "application/json")
                            .timeout(TIMEOUT)
                            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                                    Map.of("app_id", appId, "app_secret", appSecret)), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode node = objectMapper.readTree(resp.body());
            if (resp.statusCode() != 200 || node.path("code").asInt(-1) != 0) {
                throw new IllegalStateException("飞书 token 获取失败：" + node.path("msg").asText(truncate(resp.body(), 120)));
            }
            String token = node.path("tenant_access_token").asText();
            int expire = node.path("expire").asInt(7200);
            try {
                redis.opsForValue().set(cacheKey, token, java.time.Duration.ofSeconds(Math.max(60, expire - 300)));
            } catch (Exception e) {
                log.debug("飞书 token 缓存写入失败：{}", e.getMessage());
            }
            return token;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("飞书 token 获取异常：" + rootMessage(e));
        }
    }

    /** 401 后清缓存，下次调用重新获取 token。 */
    private void clearFeishuToken(McpServer server) {
        try {
            redis.delete("mcp:feishu-token:" + server.getId());
        } catch (Exception ignored) { }
    }

    private Map<String, Object> authOf(McpServer server) {
        if (server.getEncryptedAuth() == null || server.getEncryptedAuth().isBlank()) return Map.of();
        try {
            String plain = cryptoService.decrypt(server.getEncryptedAuth());
            if (plain == null || plain.isBlank()) return Map.of();
            return objectMapper.readValue(plain, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("MCP 鉴权信息解密失败：AES_KEY 可能已变更");
        }
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(300L * (attempt + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private String truncate(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private String rootMessage(Throwable e) {
        if (e == null) return "未知错误";
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    /** STDIO 子进程的 PATH 兜底目录（node/python 常见安装位），避免 env: node: No such file。 */
    private String extraPathEntries() {
        String home = System.getProperty("user.home", "");
        return String.join(":",
                home + "/tools/node-v22.14.0-darwin-arm64/bin",
                home + "/.local/bin",
                "/opt/homebrew/bin",
                "/usr/local/bin",
                "/usr/bin",
                "/bin");
    }
}
