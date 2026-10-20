package com.platform.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.AgentKnowledgeBase;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.entity.IntegrationApiKey;
import com.platform.agent.mapper.AgentKnowledgeBaseMapper;
import com.platform.conversation.entity.Conversation;
import com.platform.conversation.mapper.ConversationMapper;
import com.platform.conversation.service.ChatStreamService;
import com.platform.knowledge.service.HybridRetrievalService;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 平台自身作为 MCP Server（反向接入）：API Key（归属 Agent）→ 暴露 ask_agent / search_knowledge。
 *
 * - ask_agent：复用平台对话链路（审核/记忆/RAG/工具/计费/Trace/配额），支持 sessionId 复用会话
 * - search_knowledge：仅允许检索该 Agent 已绑定的知识库（租户隔离）
 * - 所有调用写审计 mcp.tool_call；API Key 的日/分钟限额由 ApiKeyService 统一校验
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpPlatformService {

    private final AgentKnowledgeBaseMapper agentKbMapper;
    private final ConversationMapper conversationMapper;
    private final ChatStreamService chatStreamService;
    private final HybridRetrievalService retrievalService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public Map<String, Object> initialize(JsonNode params) {
        String requested = params == null ? "" : params.path("protocolVersion").asText("");
        String version = List.of("2024-11-05", "2025-03-26").contains(requested)
                ? requested : "2025-03-26";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", version);
        result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        result.put("serverInfo", Map.of("name", "ai-agent-platform", "version", "1.0"));
        result.put("instructions", "调用 ask_agent 向已发布 Agent 提问；search_knowledge 检索该 Agent 绑定的知识库。");
        return result;
    }

    public Map<String, Object> toolsList(IntegrationApiKey key, AgentRegistry agent) {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(askAgentTool(agent));
        if (!bindings(agent.getId()).isEmpty()) tools.add(searchTool());
        return Map.of("tools", tools);
    }

    public Map<String, Object> callTool(IntegrationApiKey key, AgentRegistry agent, JsonNode params) {
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");
        try {
            return switch (name) {
                case "ask_agent" -> askAgent(key, agent, args);
                case "search_knowledge" -> searchKnowledge(key, agent, args);
                default -> toolError("未知工具：" + name);
            };
        } catch (Exception e) {
            log.warn("MCP 工具执行失败 key={} tool={}：{}", key.getId(), name, e.getMessage());
            audit(key, agent, name, "FAILED", Map.of("error", rootMessage(e)));
            return toolError(rootMessage(e));
        }
    }

    // ---------- ask_agent ----------

    private Map<String, Object> askAgentTool(AgentRegistry agent) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("message", Map.of("type", "string", "description", "发送给 Agent 的消息"));
        properties.put("sessionId", Map.of("type", "string", "description", "可选；复用已有会话"));
        properties.put("endUserId", Map.of("type", "string", "description", "可选；外部用户标识"));
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", "ask_agent");
        tool.put("title", agent.getName());
        tool.put("description", "向已发布 Agent「" + agent.getName() + "」提问"
                + (agent.getDescription() == null || agent.getDescription().isBlank()
                ? "" : "：" + agent.getDescription()));
        tool.put("inputSchema", Map.of("type", "object", "properties", properties,
                "required", List.of("message")));
        tool.put("outputSchema", Map.of("type", "object", "properties", Map.of(
                "answer", Map.of("type", "string"),
                "sessionId", Map.of("type", "string"),
                "conversationId", Map.of("type", "integer"),
                "traceId", Map.of("type", "string"))));
        tool.put("annotations", Map.of("readOnlyHint", false, "destructiveHint", false,
                "idempotentHint", false, "openWorldHint", true));
        return tool;
    }

    private Map<String, Object> askAgent(IntegrationApiKey key, AgentRegistry agent, JsonNode args) {
        String message = args.path("message").asText("");
        if (message.isBlank()) return toolError("message 不能为空");
        String sessionId = args.path("sessionId").asText("");
        String endUserId = args.path("endUserId").asText("");
        Conversation conversation = resolveConversation(key, agent,
                sessionId == null || sessionId.isBlank() ? null : sessionId,
                endUserId == null || endUserId.isBlank() ? null : endUserId);
        JwtPrincipal principal = new JwtPrincipal(key.getServiceUserId(), key.getTenantId(), false,
                List.of(), List.of());
        Object result = chatStreamService.sendWithSource(conversation.getId(),
                new ChatStreamService.SendRequest(message, false, Map.of(), null), principal, "mcp");
        Map<?, ?> map = result instanceof Map<?, ?> m ? m : Map.of();
        String answer = str(map.get("answer"));
        String traceId = str(map.get("traceId"));
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("answer", answer);
        structured.put("sessionId", conversation.getSessionId());
        structured.put("conversationId", conversation.getId());
        structured.put("traceId", traceId);
        if (map.get("usage") instanceof Map<?, ?> usage) {
            structured.put("usage", usage);
        }
        audit(key, agent, "ask_agent", "SUCCESS", Map.of(
                "sessionId", conversation.getSessionId(), "traceId", traceId == null ? "" : traceId));
        Map<String, Object> toolResult = new LinkedHashMap<>();
        toolResult.put("content", List.of(Map.of("type", "text", "text", answer == null ? "" : answer)));
        toolResult.put("structuredContent", structured);
        toolResult.put("isError", false);
        return toolResult;
    }

    private Conversation resolveConversation(IntegrationApiKey key, AgentRegistry agent,
                                             String sessionId, String endUserId) {
        if (sessionId != null && !sessionId.isBlank()) {
            Conversation existing = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                    .eq(Conversation::getSessionId, sessionId).last("LIMIT 1"));
            if (existing != null) {
                if (!existing.getAgentId().equals(agent.getId())) {
                    throw new IllegalStateException("sessionId 已绑定其他 Agent");
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
        c.setTitle(agent.getName() + "（MCP）");
        c.setSource("mcp");
        c.setStatus("ACTIVE");
        c.setTenantId(key.getTenantId());
        c.setLastActiveAt(LocalDateTime.now());
        c.setCreatedAt(LocalDateTime.now());
        conversationMapper.insert(c);
        return c;
    }

    // ---------- search_knowledge ----------

    private Map<String, Object> searchTool() {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", "search_knowledge");
        tool.put("description", "检索该 Agent 已绑定的知识库（返回相关分段与相似度）");
        tool.put("inputSchema", Map.of("type", "object", "properties", Map.of(
                "query", Map.of("type", "string", "description", "检索问题"),
                "knowledgeBaseId", Map.of("type", "integer", "description", "可选；不传则检索全部已绑定知识库"),
                "topK", Map.of("type", "integer", "description", "返回条数，默认 5，最大 20")),
                "required", List.of("query")));
        tool.put("annotations", Map.of("readOnlyHint", true, "destructiveHint", false,
                "idempotentHint", true, "openWorldHint", false));
        return tool;
    }

    private Map<String, Object> searchKnowledge(IntegrationApiKey key, AgentRegistry agent, JsonNode args) {
        String query = args.path("query").asText("");
        if (query.isBlank()) return toolError("query 不能为空");
        List<AgentKnowledgeBase> bindings = bindings(agent.getId());
        if (bindings.isEmpty()) return toolError("该 Agent 未绑定知识库");
        long requested = args.path("knowledgeBaseId").asLong(0);
        Set<Long> allowed = new LinkedHashSet<>();
        for (AgentKnowledgeBase b : bindings) allowed.add(b.getKnowledgeBaseId());
        if (requested > 0 && !allowed.contains(requested)) {
            return toolError("知识库未绑定到该 Agent：" + requested);
        }
        int topK = Math.max(1, Math.min(args.path("topK").asInt(5), 20));
        List<Map<String, Object>> hits = new ArrayList<>();
        for (AgentKnowledgeBase binding : bindings) {
            if (requested > 0 && !binding.getKnowledgeBaseId().equals(requested)) continue;
            Map<String, Object> config = parseMap(binding.getRetrievalConfig());
            HybridRetrievalService.Result result = retrievalService.search(key.getTenantId(), query,
                    binding.getKnowledgeBaseId(), topK, 0.25, config);
            for (Map<String, Object> hit : result.hits()) {
                Map<String, Object> row = new LinkedHashMap<>(hit);
                row.put("knowledgeBaseId", binding.getKnowledgeBaseId());
                hits.add(row);
            }
        }
        hits.sort(Comparator.comparingDouble((Map<String, Object> h) -> {
            Object score = h.get("score");
            return score instanceof Number n ? n.doubleValue() : 0d;
        }).reversed());
        if (hits.size() > topK) hits = new ArrayList<>(hits.subList(0, topK));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            if (i > 0) text.append("\n\n");
            text.append('[').append(i + 1).append("] ")
                    .append(str(hits.get(i).get("content")));
        }
        if (text.length() == 0) text.append("未检索到相关内容");
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("query", query);
        structured.put("hits", hits);
        structured.put("total", hits.size());
        audit(key, agent, "search_knowledge", "SUCCESS",
                Map.of("query", truncate(query, 200), "hits", hits.size()));
        Map<String, Object> toolResult = new LinkedHashMap<>();
        toolResult.put("content", List.of(Map.of("type", "text", "text", text.toString())));
        toolResult.put("structuredContent", structured);
        toolResult.put("isError", false);
        return toolResult;
    }

    private List<AgentKnowledgeBase> bindings(Long agentId) {
        return agentKbMapper.selectList(new LambdaQueryWrapper<AgentKnowledgeBase>()
                .eq(AgentKnowledgeBase::getAgentId, agentId)
                .eq(AgentKnowledgeBase::getEnabled, 1));
    }

    private void audit(IntegrationApiKey key, AgentRegistry agent, String tool,
                       String status, Map<String, Object> detail) {
        try {
            Map<String, Object> data = new LinkedHashMap<>(detail);
            data.put("tool", tool);
            data.put("keyId", key.getId());
            auditService.record(key.getServiceUserId(), "mcp.tool_call", "agent",
                    String.valueOf(agent.getId()), status, data);
        } catch (Exception e) {
            log.debug("MCP 审计写入失败：{}", e.getMessage());
        }
    }

    private Map<String, Object> toolError(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", message)));
        result.put("isError", true);
        return result;
    }

    private Map<String, Object> parseMap(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(raw, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String truncate(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
