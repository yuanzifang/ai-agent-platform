package com.platform.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.McpServer;
import com.platform.common.security.CryptoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCP 客户端（需求 3.15）：支持 Streamable HTTP 与旧版 SSE 传输。
 * - HTTP：POST JSON-RPC，兼容 application/json 与 SSE 响应（飞书等云 MCP 常用）
 * - SSE：GET 建立事件流，等待 endpoint 事件后向 messages 地址 POST（智谱/部分自建 MCP 常用）
 * - 支持认证（API Key/Bearer/OAuth/Basic）与自定义请求头；读操作带指数退避重试
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpClientService {

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int READ_RETRIES = 2;

    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;

    public record McpTool(String name, String description, String inputSchema) {
    }

    public record McpCallResult(String text, boolean error) {
    }

    public List<McpTool> listTools(McpServer server) {
        Exception last = null;
        for (int attempt = 0; attempt <= READ_RETRIES; attempt++) {
            try {
                return isSse(server) ? listToolsSse(server) : listToolsHttp(server);
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
        Exception last = null;
        // 写操作只重试一次，避免重复副作用
        for (int attempt = 0; attempt <= 1; attempt++) {
            try {
                JsonNode result = isSse(server)
                        ? rpcSse(server, "tools/call", params)
                        : rpcHttp(server, "tools/call", params);
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

    // ---------- HTTP 传输 ----------

    private List<McpTool> listToolsHttp(McpServer server) throws Exception {
        String sessionId = initializeHttp(server);
        JsonNode result = rpcHttpWithSession(server, sessionId, "tools/list", objectMapper.createObjectNode(), 2);
        return parseTools(result);
    }

    private JsonNode rpcHttp(McpServer server, String method, Object params) throws Exception {
        String sessionId = initializeHttp(server);
        int id = "tools/call".equals(method) ? 3 : 2;
        return rpcHttpWithSession(server, sessionId, method, params instanceof JsonNode n ? n
                : objectMapper.valueToTree(params), id);
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
        return sessionId;
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
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + "：" + truncate(resp.body(), 300));
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

    // ---------- SSE 传输 ----------

    private List<McpTool> listToolsSse(McpServer server) throws Exception {
        return parseTools(rpcSse(server, "tools/list", objectMapper.createObjectNode()));
    }

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

    private boolean isSse(McpServer server) {
        return "SSE".equalsIgnoreCase(server.getTransport());
    }

    private List<McpTool> parseTools(JsonNode result) {
        List<McpTool> tools = new ArrayList<>();
        JsonNode arr = result.path("tools");
        if (arr.isArray()) {
            for (JsonNode t : arr) {
                String name = t.path("name").asText(null);
                if (name == null || name.isBlank()) continue;
                JsonNode schema = t.get("inputSchema");
                tools.add(new McpTool(name, t.path("description").asText(""),
                        schema == null ? null : schema.toString()));
            }
        }
        return tools;
    }

    private McpCallResult parseCallResult(JsonNode result) {
        boolean error = result.path("isError").asBoolean(false);
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
        if (text.length() == 0) text.append(result.toString());
        return new McpCallResult(text.toString(), error);
    }

    private void applyAuth(HttpRequest.Builder builder, McpServer server) {
        String type = server.getAuthType() == null ? "NONE" : server.getAuthType().toUpperCase();
        if ("NONE".equals(type)) return;
        Map<String, Object> auth = authOf(server);
        switch (type) {
            case "API_KEY" -> builder.header(
                    str(auth.getOrDefault("header", "X-API-Key")), str(auth.get("value")));
            case "BEARER", "OAUTH" -> builder.header("Authorization", "Bearer " + str(auth.get("token")));
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
