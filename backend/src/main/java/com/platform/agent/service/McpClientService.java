package com.platform.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.McpServer;
import com.platform.common.security.CryptoService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final McpOAuthService oauthService;

    private final Map<Long, CachedHttpSession> httpSessions = new ConcurrentHashMap<>();
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

    private record CachedHttpSession(String sessionId, long expiresAt) {
    }

    @PostConstruct
    void init() {
        maintenance.scheduleWithFixedDelay(this::cleanup, 60, 60, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        maintenance.shutdownNow();
        stdioSessions.values().forEach(StdioSession::close);
        stdioSessions.clear();
    }

    // ---------- 对外入口 ----------

    public List<McpTool> listTools(McpServer server) {
        Exception last = null;
        for (int attempt = 0; attempt <= READ_RETRIES; attempt++) {
            try {
                JsonNode result = rpc(server, "tools/list", objectMapper.createObjectNode(), TIMEOUT);
                return parseTools(result);
            } catch (Exception e) {
                last = e;
                log.warn("MCP tools/list 失败（第 {} 次）server={}：{}", attempt + 1, server.getId(), rootMessage(e));
                backoff(attempt);
            }
        }
        throw new IllegalStateException(rootMessage(last));
    }

    public McpCallResult callTool(McpServer server, String toolName, Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments == null ? Map.of() : arguments);
        params.put("_meta", Map.of("progressToken", "p-" + UUID.randomUUID()));
        Exception last = null;
        // 写操作只重试一次，避免重复副作用
        for (int attempt = 0; attempt <= 1; attempt++) {
            try {
                JsonNode result = rpc(server, "tools/call", params, CALL_TIMEOUT);
                return parseCallResult(result);
            } catch (Exception e) {
                last = e;
                log.warn("MCP tools/call 失败（第 {} 次）server={} tool={}：{}",
                        attempt + 1, server.getId(), toolName, rootMessage(e));
                backoff(attempt);
            }
        }
        throw new IllegalStateException(rootMessage(last));
    }

    private JsonNode rpc(McpServer server, String method, Object params, Duration timeout) throws Exception {
        String transport = server.getTransport() == null ? "HTTP" : server.getTransport().toUpperCase(Locale.ROOT);
        return switch (transport) {
            case "STDIO" -> stdioSession(server).rpc(method, params, timeout);
            case "SSE" -> rpcSse(server, method, params);
            default -> rpcHttp(server, method, params);
        };
    }

    // ---------- HTTP 传输 ----------

    private JsonNode rpcHttp(McpServer server, String method, Object params) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String sessionId = httpSessionId(server);
            try {
                JsonNode node = rpcHttpWithSession(server, sessionId, method,
                        params instanceof JsonNode n ? n : objectMapper.valueToTree(params), 2);
                touchHttpSession(server.getId());
                return node;
            } catch (SessionExpiredException e) {
                last = e;
                log.info("MCP HTTP 会话失效，重新 initialize：server={}", server.getId());
                invalidateHttpSession(server.getId());
            } catch (UnauthorizedException e) {
                last = e;
                if (!"OAUTH".equalsIgnoreCase(server.getAuthType())) throw e;
                log.info("MCP OAuth 401，强制刷新 token 后重试：server={}", server.getId());
                oauthService.forceRefresh(server);
                invalidateHttpSession(server.getId());
            }
        }
        throw last;
    }

    private String httpSessionId(McpServer server) throws Exception {
        CachedHttpSession cached = httpSessions.get(server.getId());
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            return cached.sessionId();
        }
        return initializeHttp(server);
    }

    private String initializeHttp(McpServer server) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "ai-agent-platform", "version", "1.0"));
        HttpResponse<String> resp = postHttp(server, Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "initialize", "params", params), null);
        String sessionId = resp.headers().firstValue("Mcp-Session-Id").orElse(null);
        JsonNode node = parseHttpResponse(resp);
        if (node.has("error")) throw new IllegalStateException("initialize 失败：" + node.path("error").path("message").asText());
        try {
            postHttp(server, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), sessionId);
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
                                        Object params, int id) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("method", method);
        body.put("params", params);
        HttpResponse<String> resp = postHttp(server, body, sessionId);
        JsonNode node = parseHttpResponse(resp);
        if (node.has("error")) throw new IllegalStateException(method + " 失败：" + node.path("error").path("message").asText());
        return node.path("result");
    }

    private HttpResponse<String> postHttp(McpServer server, Object body, String sessionId) throws Exception {
        HttpRequest.Builder builder = requestBuilder(server)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (sessionId != null && !sessionId.isBlank()) builder.header("Mcp-Session-Id", sessionId);
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        HttpResponse<String> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
                } catch (Exception ignored) { }
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

    private JsonNode rpcSse(McpServer server, String method, Object params) throws Exception {
        try (SseSession session = SseSession.open(server, this)) {
            session.rpc(1, "initialize", Map.of(
                    "protocolVersion", PROTOCOL_VERSION,
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "ai-agent-platform", "version", "1.0")));
            try {
                session.notify("notifications/initialized", Map.of());
            } catch (Exception ignored) { }
            return session.rpc(2, method, params);
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
            HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
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
                int id = node.path("id").asInt(-1);
                CompletableFuture<JsonNode> future = pending.remove(id);
                if (future != null) future.complete(node);
            } catch (Exception ignored) { }
        }

        JsonNode rpc(int id, String method, Object params) throws Exception {
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
                node = future.get(25, TimeUnit.SECONDS);
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
            } catch (Exception ignored) { }
            try {
                reader.close();
            } catch (Exception ignored) { }
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
            pb.directory(owner.stdioWorkDir(server));
            Process process = pb.start();
            StdioSession session = new StdioSession(server, owner, process);
            session.readerThread.start();
            session.stderrThread.start();
            try {
                session.rpc("initialize", Map.of(
                        "protocolVersion", PROTOCOL_VERSION,
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "ai-agent-platform", "version", "1.0")), TIMEOUT);
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

        JsonNode rpc(String method, Object params, Duration timeout) throws Exception {
            if (!healthy()) throw new IllegalStateException("MCP stdio 进程不可用（command=" + server.getCommand() + "）");
            int id = seq.getAndIncrement();
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            pending.put(id, future);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jsonrpc", "2.0");
            body.put("id", id);
            body.put("method", method);
            body.put("params", params);
            try {
                send(body);
                touch();
                JsonNode node = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (node.has("error")) {
                    throw new IllegalStateException(method + " 失败：" + node.path("error").path("message").asText());
                }
                return node.path("result");
            } catch (TimeoutException e) {
                // 协议 2025-03-26：超时后尽力发送取消通知
                try {
                    notify("notifications/cancelled", Map.of("requestId", id, "reason", "timeout"));
                } catch (Exception ignored) { }
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
            } catch (Exception ignored) { }
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
            } catch (Exception ignored) { }
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

    private List<McpTool> parseTools(JsonNode result) {
        List<McpTool> tools = new ArrayList<>();
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
}
