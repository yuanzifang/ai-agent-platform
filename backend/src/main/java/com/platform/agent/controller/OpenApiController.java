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
@RequiredArgsConstructor
public class OpenApiController {

    private final ApiKeyService apiKeyService;
    private final AgentRegistryMapper agentMapper;
    private final ConversationMapper conversationMapper;
    private final ChatStreamService chatStreamService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/chat/completions",
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public ResponseEntity<?> chatCompletions(
            @RequestHeader(value = "X-Api-Key", required = false) String xApiKey,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        String rawKey = extractKey(xApiKey, authorization);
        IntegrationApiKey key = apiKeyService.authenticate(rawKey);
        AgentRegistry agent = TenantContext.runWith(key.getTenantId(),
                () -> agentMapper.selectById(key.getAgentId()));
        if (agent == null || !"PUBLISHED".equals(agent.getStatus())) {
            throw new BusinessException(403, "Agent 未发布，无法调用");
        }
        return TenantContext.runWith(key.getTenantId(), () -> handle(key, agent, body));
    }

    private ResponseEntity<?> handle(IntegrationApiKey key, AgentRegistry agent, Map<String, Object> body) {
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
        Object result = chatStreamService.send(conversation.getId(),
                new ChatStreamService.SendRequest(message, false, Map.of(), null), principal);
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
                        "endUserId", endUserId == null ? "" : endUserId));

        String model = modelName(agent);
        String completionId = "chatcmpl-" + conversation.getSessionId();
        long created = Instant.now().getEpochSecond();
        if (stream) {
            String sse = buildStream(completionId, created, model, answer, inputTokens, outputTokens,
                    conversation.getSessionId());
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(sse);
        }
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

    private String buildStream(String id, long created, String model, String answer,
                               long inputTokens, long outputTokens, String sessionId) {
        try {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", id);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", created);
            chunk.put("model", model);
            Map<String, Object> deltaChoice = new LinkedHashMap<>();
            deltaChoice.put("index", 0);
            deltaChoice.put("delta", Map.of("role", "assistant", "content", answer == null ? "" : answer));
            deltaChoice.put("finish_reason", null);
            chunk.put("choices", List.of(deltaChoice));
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("id", id);
            done.put("object", "chat.completion.chunk");
            done.put("created", created);
            done.put("model", model);
            done.put("choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", "stop")));
            done.put("usage", Map.of("prompt_tokens", inputTokens, "completion_tokens", outputTokens,
                    "total_tokens", inputTokens + outputTokens));
            done.put("session_id", sessionId);
            return "data: " + objectMapper.writeValueAsString(chunk) + "\n\n"
                    + "data: " + objectMapper.writeValueAsString(done) + "\n\n"
                    + "data: [DONE]\n\n";
        } catch (Exception e) {
            throw new BusinessException(500, "流式响应序列化失败");
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
