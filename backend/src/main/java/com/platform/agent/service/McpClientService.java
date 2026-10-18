package com.platform.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.McpServer;
import com.platform.common.security.CryptoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

/**
 * MCP 客户端（需求 3.15 / 架构 MCP 集成）：JSON-RPC 2.0 over HTTP（Streamable HTTP，兼容 SSE 响应）。
 * initialize → notifications/initialized → tools/list / tools/call。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpClientService {

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;

    public record McpTool(String name, String description, String inputSchema) {
    }

    public record McpCallResult(String text, boolean error) {
    }

    public List<McpTool> listTools(McpServer server) {
        try {
            String sessionId = initialize(server);
            JsonNode result = rpc(server, sessionId, "tools/list", objectMapper.createObjectNode(), 2);
            List<McpTool> tools = new ArrayList<>();
            JsonNode arr = result.path("tools");
            if (arr.isArray()) {
                for (JsonNode t : arr) {
                    String name = t.path("name").asText(null);
                    if (name == null || name.isBlank()) continue;
                    String desc = t.path("description").asText("");
                    JsonNode schema = t.get("inputSchema");
                    tools.add(new McpTool(name, desc, schema == null ? null : schema.toString()));
                }
            }
            return tools;
        } catch (Exception e) {
            throw new IllegalStateException(rootMessage(e), e);
        }
    }

    public McpCallResult callTool(McpServer server, String toolName, Map<String, Object> arguments) {
        try {
            String sessionId = initialize(server);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", toolName);
            params.put("arguments", arguments == null ? Map.of() : arguments);
            JsonNode result = rpc(server, sessionId, "tools/call", objectMapper.valueToTree(params), 3);
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
        } catch (Exception e) {
            throw new IllegalStateException(rootMessage(e), e);
        }
    }

    // ---------- JSON-RPC ----------

    private String initialize(McpServer server) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "ai-agent-platform", "version", "1.0"));
        HttpResponse<String> resp = post(server, Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "initialize", "params", params), null);
        String sessionId = resp.headers().firstValue("Mcp-Session-Id").orElse(null);
        JsonNode node = parseResponse(resp);
        if (node.has("error")) {
            throw new IllegalStateException("initialize 失败：" + node.path("error").path("message").asText());
        }
        // 通知（无响应）
        try {
            post(server, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), sessionId);
        } catch (Exception e) {
            log.debug("MCP initialized 通知失败（忽略）：{}", e.getMessage());
        }
        return sessionId;
    }

    private JsonNode rpc(McpServer server, String sessionId, String method, Object params, int id) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("method", method);
        body.put("params", params);
        HttpResponse<String> resp = post(server, body, sessionId);
        JsonNode node = parseResponse(resp);
        if (node.has("error")) {
            throw new IllegalStateException(method + " 失败：" + node.path("error").path("message").asText());
        }
        return node.path("result");
    }

    private HttpResponse<String> post(McpServer server, Object body, String sessionId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.getEndpoint()))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (sessionId != null && !sessionId.isBlank()) builder.header("Mcp-Session-Id", sessionId);
        applyAuth(builder, server);
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        HttpResponse<String> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + "："
                    + truncate(resp.body(), 300));
        }
        return resp;
    }

    /** 兼容 application/json 与 text/event-stream（取最后一个 JSON-RPC 响应）。 */
    private JsonNode parseResponse(HttpResponse<String> resp) throws Exception {
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
                } catch (Exception ignored) {
                    // 忽略心跳等非 JSON 数据
                }
            }
            if (last == null) throw new IllegalStateException("SSE 响应中没有 JSON-RPC 数据");
            return last;
        }
        return objectMapper.readTree(body);
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

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private String truncate(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
