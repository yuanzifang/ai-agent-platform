package com.platform.agent.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.entity.IntegrationApiKey;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.agent.service.ApiKeyService;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.conversation.entity.Conversation;
import com.platform.conversation.mapper.ConversationMapper;
import com.platform.conversation.service.ChatStreamService;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 外部集成对话（需求 7.1/7.2）：API Key 鉴权，OpenAI 兼容 /api/v1/open/chat/completions。
 * - X-Api-Key: {key} 或 Authorization: ApiKey {key} / Bearer {key}
 * - 会话 source=api，session_id 复用；服务账号作为 user_id（is_service_account=1），endUserId 记录外部用户
 * - 复用平台对话链路（审核/记忆/RAG/工具/计费/Trace/配额）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/open")
@io.swagger.v3.oas.annotations.tags.Tag(name = "开放 API", description = "第三方集成：网页挂件与对话补全（X-API-Key 鉴权）")
@RequiredArgsConstructor
public class OpenApiController {

    private final ApiKeyService apiKeyService;
    private final com.platform.common.ratelimit.RedisRateLimiter rateLimiter;
    private final AgentRegistryMapper agentMapper;
    private final ConversationMapper conversationMapper;
    private final ChatStreamService chatStreamService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /** 网页挂件初始化：返回 Agent 展示信息（API Key 鉴权）。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "挂件初始化", description = "返回 Agent 名称/描述/欢迎语等展示信息")
    @GetMapping("/agent/info")
    public com.platform.common.response.ApiResponse<Map<String, Object>> agentInfo(
            @RequestHeader(value = "X-Api-Key", required = false) String xApiKey,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        IntegrationApiKey key = apiKeyService.authenticate(extractKey(xApiKey, authorization));
        AgentRegistry agent = TenantContext.runWith(key.getTenantId(),
                () -> agentMapper.selectById(key.getAgentId()));
        if (agent == null || !"PUBLISHED".equals(agent.getStatus())) {
            throw new BusinessException(403, "Agent 未发布，无法使用挂件");
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("agentId", agent.getId());
        info.put("name", applyVars(agent.getName(), agent.getVariables()));
        info.put("description", applyVars(agent.getDescription(), agent.getVariables()));
        info.put("openingStatement", applyVars(agent.getOpeningStatement(), agent.getVariables()));
        info.put("avatarUrl", agent.getAvatarUrl());
        info.put("category", agent.getCategory());
        return com.platform.common.response.ApiResponse.ok(info);
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "对话补全（OpenAI 兼容）",
            description = "stream=true 时返回 SSE chunk 流（role/content/finish_reason/usage/[DONE]）")
    @PostMapping(value = "/chat/completions",
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public Object chatCompletions(
            @RequestHeader(value = "X-Api-Key", required = false) String xApiKey,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Gray-Tag", required = false) String grayTagHeader,
            @RequestBody Map<String, Object> body,
            jakarta.servlet.http.HttpServletResponse httpResponse) {
        String rawKey = extractKey(xApiKey, authorization);
        IntegrationApiKey key = apiKeyService.authenticate(rawKey);
        // 开放入口限流（按 Key 桶、租户配额值）：此前该入口完全不在限流范围内
        int limit = rateLimiter.tenantLimitPerMinute(key.getTenantId());
        if (limit < 0) limit = 60;
        if (!rateLimiter.allow("ratelimit:open:" + key.getId() + ":" + (System.currentTimeMillis() / 60_000), limit)) {
            throw new com.platform.common.exception.BusinessException(429, "开放接口限流（" + limit + "/分钟）");
        }
        AgentRegistry agent = TenantContext.runWith(key.getTenantId(),
                () -> agentMapper.selectById(key.getAgentId()));
        if (agent == null || !"PUBLISHED".equals(agent.getStatus())) {
            throw new BusinessException(403, "Agent 未发布，无法调用");
        }
        String grayTag = ChatStreamService.sanitizeGrayTag(grayTagHeader);
        String finalGrayTag = grayTag;
        if (grayTag != null) httpResponse.setHeader("X-Gray-Tag", grayTag);   // 回显便于客户端确认命中
        return TenantContext.runWith(key.getTenantId(), () -> handle(key, agent, body, finalGrayTag, httpResponse));
    }

    private Object handle(IntegrationApiKey key, AgentRegistry agent, Map<String, Object> body,
                          String grayTag, jakarta.servlet.http.HttpServletResponse httpResponse) {
        String message = extractUserMessage(body.get("messages"));
        if (message == null || message.isBlank()) {
            throw new BusinessException(400, "messages 中缺少 user 消息");
        }
        String sessionId = str(body.get("session_id"));
        if (sessionId == null) sessionId = str(body.get("sessionId"));
        String endUserId = str(body.get("endUserId"));
        if (endUserId == null) endUserId = str(body.get("end_user_id"));
        boolean stream = Boolean.TRUE.equals(body.get("stream")) || "true".equals(str(body.get("stream")));

        Conversation conversation = resolveConversation(key, agent, sessionId, endUserId);
        JwtPrincipal principal = new JwtPrincipal(key.getServiceUserId(), key.getTenantId(), false,
                List.of(), List.of());
        String model = modelName(agent);
        String completionId = "chatcmpl-" + conversation.getSessionId();
        long created = Instant.now().getEpochSecond();

        if (stream) {
            httpResponse.setHeader("Cache-Control", "no-cache");
            httpResponse.setHeader("X-Accel-Buffering", "no");
            httpResponse.setHeader("Connection", "keep-alive");
            OpenAiSseSink sink = new OpenAiSseSink(completionId, created, model,
                    conversation.getSessionId(), objectMapper);
            chatStreamService.sendStreaming(conversation.getId(),
                    new ChatStreamService.SendRequest(message, true, Map.of(), null, null, grayTag),
                    principal, "api", sink);
            auditService.record(key.getServiceUserId(), "api_key.chat", "agent",
                    String.valueOf(agent.getId()), "SUCCESS",
                    Map.of("keyId", key.getId(), "sessionId", conversation.getSessionId(),
                            "endUserId", endUserId == null ? "" : endUserId,
                            "grayTag", grayTag == null ? "" : grayTag));
            return sink.emitter();
        }

        Object result = chatStreamService.sendWithSource(conversation.getId(),
                new ChatStreamService.SendRequest(message, false, Map.of(), null, null, grayTag),
                principal, "api");
        if (!(result instanceof Map<?, ?> map)) {
            throw new BusinessException(500, "对话执行失败");
        }
        String answer = str(map.get("answer"));
        long inputTokens = 0;
        long outputTokens = 0;
        if (map.get("usage") instanceof Map<?, ?> usage) {
            inputTokens = longValue(usage.get("inputTokens"));
            outputTokens = longValue(usage.get("outputTokens"));
        }
        String traceId = str(map.get("traceId"));
        auditService.record(key.getServiceUserId(), "api_key.chat", "agent",
                String.valueOf(agent.getId()), "SUCCESS",
                Map.of("keyId", key.getId(), "sessionId", conversation.getSessionId(),
                        "endUserId", endUserId == null ? "" : endUserId,
                        "grayTag", grayTag == null ? "" : grayTag));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", completionId);
        response.put("object", "chat.completion");
        response.put("created", created);
        response.put("model", model);
        response.put("choices", List.of(Map.of(
                "index", 0,
                "message", Map.of("role", "assistant", "content", answer == null ? "" : answer),
                "finish_reason", "stop")));
        response.put("usage", Map.of(
                "prompt_tokens", inputTokens,
                "completion_tokens", outputTokens,
                "total_tokens", inputTokens + outputTokens));
        response.put("session_id", conversation.getSessionId());
        response.put("conversation_id", conversation.getId());
        response.put("trace_id", traceId);
        return ResponseEntity.ok(response);
    }

    /** session_id 复用（同 Agent）；否则创建 source=api 会话，记录 endUserId。 */
    private Conversation resolveConversation(IntegrationApiKey key, AgentRegistry agent,
                                             String sessionId, String endUserId) {
        if (sessionId != null && !sessionId.isBlank()) {
            Conversation existing = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                    .eq(Conversation::getSessionId, sessionId)
                    .last("LIMIT 1"));
            if (existing != null) {
                if (!existing.getAgentId().equals(agent.getId())) {
                    throw new BusinessException(409, "session_id 已绑定其他 Agent");
                }
                if (endUserId != null && !endUserId.isBlank()) {
                    existing.setExternalUserId(endUserId);
                    conversationMapper.updateById(existing);
                }
                return existing;
            }
        }
        Conversation c = new Conversation();
        c.setSessionId(sessionId == null || sessionId.isBlank()
                ? UUID.randomUUID().toString().replace("-", "") : sessionId.trim());
        c.setAgentId(agent.getId());
        c.setUserId(key.getServiceUserId());
        c.setExternalUserId(endUserId);
        c.setTitle(agent.getName() + "（API）");
        c.setSource("api");
        c.setStatus("ACTIVE");
        c.setTenantId(key.getTenantId());
        c.setLastActiveAt(LocalDateTime.now());
        c.setCreatedAt(LocalDateTime.now());
        conversationMapper.insert(c);
        return c;
    }

    private String extractUserMessage(Object messages) {
        if (!(messages instanceof List<?> list)) return null;
        String content = null;
        for (Object o : list) {
            Map<String, Object> m = o instanceof Map<?, ?> map ? castMap(map) : Map.of();
            if (!"user".equals(str(m.get("role")))) continue;
            content = contentOf(m.get("content"));
        }
        return content;
    }

    private String contentOf(Object content) {
        if (content == null) return null;
        if (content instanceof String s) return s;
        if (content instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object p : parts) {
                Map<String, Object> m = p instanceof Map<?, ?> map ? castMap(map) : Map.of();
                if (m.get("text") != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(str(m.get("text")));
                }
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    private String extractKey(String xApiKey, String authorization) {
        if (xApiKey != null && !xApiKey.isBlank()) return xApiKey.trim();
        if (authorization == null || authorization.isBlank()) return null;
        String v = authorization.trim();
        if (v.toLowerCase().startsWith("apikey ")) return v.substring(7).trim();
        if (v.toLowerCase().startsWith("bearer ")) return v.substring(7).trim();
        return v;
    }

    /**
     * OpenAI SSE chunk 输出：delta 实时转发，done 时补 finish_reason/usage，最后 [DONE]。
     * 平台内部事件（citation/tool/moderation）不暴露给 OpenAI 客户端。
     */
    private static final class OpenAiSseSink implements ChatStreamService.StreamSink {
        private final SseEmitter emitter = new SseEmitter(300_000L);
        private final ObjectMapper mapper;
        private final String id;
        private final long created;
        private final String model;
        private final String sessionId;
        private long inputTokens;
        private long outputTokens;
        private String traceId;
        private String status = "SUCCESS";
        private boolean roleSent;
        private boolean finished;
        private boolean failed;

        private OpenAiSseSink(String id, long created, String model, String sessionId, ObjectMapper mapper) {
            this.id = id;
            this.created = created;
            this.model = model;
            this.sessionId = sessionId;
            this.mapper = mapper;
        }

        @Override
        public synchronized void event(String name, Object data) {
            if (failed && !"error".equals(name)) return;
            try {
                switch (name) {
                    case "delta" -> {
                        String content = contentOf(data);
                        if (content != null && !content.isEmpty()) {
                            if (!roleSent) {
                                Map<String, Object> role = new LinkedHashMap<>();
                                role.put("role", "assistant");
                                role.put("content", "");
                                sendDelta(role);
                                roleSent = true;
                            }
                            Map<String, Object> delta = new LinkedHashMap<>();
                            delta.put("content", content);
                            sendDelta(delta);
                        }
                    }
                    case "usage" -> captureUsage(data);
                    case "meta" -> captureMeta(data);
                    case "done" -> {
                        captureUsage(data);
                        captureMeta(data);
                        if (data instanceof Map<?, ?> m && m.get("status") != null) {
                            status = String.valueOf(m.get("status"));
                        }
                        if (!failed) sendFinal();
                    }
                    case "error" -> {
                        failed = true;
                        sendError(data);
                    }
                    default -> {
                        // citation / moderation / tool 等平台事件不输出给 OpenAI 客户端
                    }
                }
            } catch (Exception e) {
                log.warn("OpenAPI SSE 写出失败：{}", e.getMessage());
                try {
                    emitter.completeWithError(e);
                } catch (Exception closeError) {
                    // 客户端可能已断开
                    log.debug("SSE completeWithError 失败（客户端可能已断开）：{}", closeError.getMessage());
                }
            }
        }

        @Override
        public synchronized void complete() {
            try {
                if (!finished && !failed) sendFinal();
            } catch (Exception e) {
                log.warn("OpenAPI SSE 完成失败：{}", e.getMessage());
            }
            emitter.complete();
        }

        @Override
        public SseEmitter emitter() {
            return emitter;
        }

        private void sendDelta(Map<String, Object> delta) throws Exception {
            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("delta", delta);
            choice.put("finish_reason", null);
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", id);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", created);
            chunk.put("model", model);
            chunk.put("choices", List.of(choice));
            sendData(mapper.writeValueAsString(chunk));
        }

        private void sendFinal() throws Exception {
            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("delta", Map.of());
            choice.put("finish_reason", "error".equalsIgnoreCase(status) ? "stop" : "stop");
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", id);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", created);
            chunk.put("model", model);
            chunk.put("choices", List.of(choice));
            chunk.put("usage", Map.of(
                    "prompt_tokens", inputTokens,
                    "completion_tokens", outputTokens,
                    "total_tokens", inputTokens + outputTokens));
            chunk.put("session_id", sessionId);
            if (traceId != null) chunk.put("trace_id", traceId);
            sendData(mapper.writeValueAsString(chunk));
            sendData("[DONE]");
            finished = true;
        }

        private void sendError(Object data) throws Exception {
            String message = "生成失败";
            Object code = 500201;
            if (data instanceof Map<?, ?> m) {
                if (m.get("message") != null) message = String.valueOf(m.get("message"));
                if (m.get("code") != null) code = m.get("code");
            }
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("message", message);
            error.put("type", "server_error");
            error.put("code", code);
            sendData(mapper.writeValueAsString(Map.of("error", error)));
            sendData("[DONE]");
            finished = true;
        }

        private void sendData(String data) throws Exception {
            emitter.send(SseEmitter.event().data(data));
        }

        private void captureUsage(Object data) {
            Object usage = data;
            if (data instanceof Map<?, ?> m && m.get("usage") instanceof Map<?, ?> u) usage = u;
            if (usage instanceof Map<?, ?> u) {
                if (u.get("inputTokens") instanceof Number n) inputTokens = n.longValue();
                if (u.get("outputTokens") instanceof Number n) outputTokens = n.longValue();
            }
        }

        private void captureMeta(Object data) {
            if (data instanceof Map<?, ?> m && m.get("traceId") != null) {
                traceId = String.valueOf(m.get("traceId"));
            }
        }

        private String contentOf(Object data) {
            if (data instanceof Map<?, ?> m && m.get("content") != null) {
                return String.valueOf(m.get("content"));
            }
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String applyVars(String text, String variablesJson) {
        if (text == null || variablesJson == null || variablesJson.isBlank()) return text;
        try {
            List<Map<String, Object>> vars = objectMapper.readValue(variablesJson, List.class);
            String result = text;
            for (Map<String, Object> v : vars) {
                Object key = v.get("key");
                Object def = v.get("defaultValue");
                if (key != null && def != null) {
                    result = result.replace("{{" + key + "}}", String.valueOf(def));
                }
            }
            return result;
        } catch (Exception e) {
            return text;
        }
    }

    private String modelName(AgentRegistry agent) {
        try {
            Map<?, ?> cfg = agent.getModelConfig() == null ? Map.of()
                    : objectMapper.readValue(agent.getModelConfig(), Map.class);
            Object model = cfg.get("modelName");
            return model == null ? "agent-" + agent.getId() : String.valueOf(model);
        } catch (Exception e) {
            return "agent-" + agent.getId();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private long longValue(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
