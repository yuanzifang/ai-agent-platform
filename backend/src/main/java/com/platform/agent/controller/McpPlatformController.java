package com.platform.agent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.entity.IntegrationApiKey;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.agent.service.ApiKeyService;
import com.platform.agent.service.McpPlatformService;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 平台作为 MCP Server（Streamable HTTP JSON-RPC）：POST /mcp。
 *
 * 鉴权：X-Api-Key / Authorization: Bearer|ApiKey（API Key 归属某个已发布 Agent）。
 * 能力：initialize / ping / tools/list / tools/call（ask_agent、search_knowledge）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class McpPlatformController {

    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final ApiKeyService apiKeyService;
    private final AgentRegistryMapper agentMapper;
    private final McpPlatformService service;

    @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> rpc(@RequestHeader(value = "X-Api-Key", required = false) String xApiKey,
                                 @RequestHeader(value = "Authorization", required = false) String authorization,
                                 @RequestBody(required = false) JsonNode body,
                                 HttpServletResponse httpResponse) {
        IntegrationApiKey key;
        try {
            key = apiKeyService.authenticate(extractKey(xApiKey, authorization));
        } catch (BusinessException e) {
            return ResponseEntity.status(httpStatus(e.getCode()))
                    .header("WWW-Authenticate", "Bearer realm=\"mcp\"")
                    .body(error(idOf(body), -32001, e.getMessage()));
        }
        AgentRegistry agent = TenantContext.runWith(key.getTenantId(),
                () -> agentMapper.selectById(key.getAgentId()));
        if (agent == null) {
            return ResponseEntity.status(404).body(error(idOf(body), -32001, "Agent 不存在"));
        }
        Object payload = TenantContext.runWith(key.getTenantId(),
                () -> process(key, agent, body, httpResponse));
        if (payload == null) {
            return ResponseEntity.status(202).build();
        }
        return ResponseEntity.ok(payload);
    }

    @DeleteMapping("/mcp")
    public ResponseEntity<Void> terminate() {
        return ResponseEntity.noContent().build();
    }

    private Object process(IntegrationApiKey key, AgentRegistry agent, JsonNode body,
                           HttpServletResponse httpResponse) {
        if (body == null || body.isNull()) return error(null, -32700, "Parse error");
        if (body.isArray()) {
            List<Object> responses = new ArrayList<>();
            for (JsonNode item : body) {
                Object response = processOne(key, agent, item, httpResponse);
                if (response != null) responses.add(response);
            }
            return responses.isEmpty() ? null : responses;
        }
        return processOne(key, agent, body, httpResponse);
    }

    private Object processOne(IntegrationApiKey key, AgentRegistry agent, JsonNode body,
                              HttpServletResponse httpResponse) {
        JsonNode id = body.get("id");
        String method = body.path("method").asText("");
        if (method.isBlank()) return error(id, -32600, "Invalid Request");
        // 通知不返回响应体
        if (method.startsWith("notifications/")) return null;
        try {
            Object result;
            switch (method) {
                case "initialize" -> {
                    httpResponse.setHeader(SESSION_HEADER, UUID.randomUUID().toString().replace("-", ""));
                    result = service.initialize(body.path("params"));
                }
                case "ping" -> result = Map.of();
                case "tools/list" -> result = service.toolsList(key, agent);
                case "tools/call" -> result = service.callTool(key, agent, body.path("params"));
                case "resources/list" -> result = Map.of("resources", List.of());
                case "prompts/list" -> result = Map.of("prompts", List.of());
                default -> {
                    return error(id, -32601, "Method not found: " + method);
                }
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            response.put("result", result);
            return response;
        } catch (Exception e) {
            log.warn("MCP JSON-RPC 处理失败 method={}：{}", method, rootMessage(e));
            return error(id, -32603, "Internal error: " + rootMessage(e));
        }
    }

    private Map<String, Object> error(JsonNode id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message == null ? "error" : message));
        return response;
    }

    private JsonNode idOf(JsonNode body) {
        if (body == null || !body.isObject()) return null;
        return body.get("id");
    }

    private String extractKey(String xApiKey, String authorization) {
        if (xApiKey != null && !xApiKey.isBlank()) return xApiKey.trim();
        if (authorization == null || authorization.isBlank()) return null;
        String value = authorization.trim();
        if (value.toLowerCase().startsWith("apikey ")) return value.substring(7).trim();
        if (value.toLowerCase().startsWith("bearer ")) return value.substring(7).trim();
        return value;
    }

    private int httpStatus(int code) {
        return code >= 400 && code < 600 ? code : 401;
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
