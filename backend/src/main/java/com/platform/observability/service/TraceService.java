package com.platform.observability.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.observability.entity.AgentSpan;
import com.platform.observability.entity.AgentTrace;
import com.platform.observability.mapper.AgentSpanMapper;
import com.platform.observability.mapper.AgentTraceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trace/Span 落库（需求 3.13）：一次对话一条 trace（agent 根 span + llm span + retrieval span）。
 * 调试链路不写正式 Trace（需求 3.1.3(10)）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraceService {

    private final AgentTraceMapper traceMapper;
    private final AgentSpanMapper spanMapper;
    private final ObjectMapper objectMapper;

    public void record(Long tenantId, String traceId, Long conversationId, String sessionId,
                       Long agentId, Long userId, String agentName, String status,
                       String modelName, long inTokens, long outTokens, long latencyMs,
                       int citationCount) {
        try {
            AgentTrace t = new AgentTrace();
            t.setTraceId(traceId);
            t.setSessionId(sessionId);
            t.setConversationId(conversationId);
            t.setAgentId(agentId);
            t.setUserId(userId);
            t.setStatus(status);
            t.setModelName(modelName);
            t.setInputTokens(inTokens);
            t.setOutputTokens(outTokens);
            t.setLatencyMs(latencyMs);
            t.setTenantId(tenantId);
            t.setCreatedAt(LocalDateTime.now());
            traceMapper.insert(t);

            // 注意：conversationId 在评测/集成链路为 null，Map.of 不允许 null 值（LinkedHashMap 可）
            Map<String, Object> agentAttrs = new LinkedHashMap<>();
            agentAttrs.put("conversationId", conversationId);
            span(tenantId, traceId, "agent", agentName == null ? "agent" : agentName,
                    status, 0, 0, latencyMs, agentAttrs);
            if (citationCount > 0) {
                span(tenantId, traceId, "retrieval", "knowledge-retrieval", "SUCCESS",
                        0, 0, 0, Map.of("hits", citationCount));
            }
            span(tenantId, traceId, "llm", modelName == null ? "llm" : modelName, status,
                    inTokens, outTokens, latencyMs, Map.of());
        } catch (Exception e) {
            log.warn("Trace 落库失败 traceId={}", traceId, e);
        }
    }

    private void span(Long tenantId, String traceId, String type, String name, String status,
                      long in, long out, long latency, Map<String, Object> attrs) {
        AgentSpan s = new AgentSpan();
        s.setTraceId(traceId);
        s.setSpanType(type);
        s.setName(name);
        s.setStatus(status);
        s.setInputTokens(in);
        s.setOutputTokens(out);
        s.setLatencyMs(latency);
        try {
            s.setAttributes(objectMapper.writeValueAsString(attrs));
        } catch (Exception ignored) {
        }
        s.setTenantId(tenantId);
        s.setCreatedAt(LocalDateTime.now());
        spanMapper.insert(s);
    }

    /** Span 视图：前端按类型着色（agent 蓝/chain 绿/llm 橙，架构 4.5）。 */
    public List<Map<String, Object>> spansOf(String traceId) {
        return spanMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentSpan>()
                                .eq(AgentSpan::getTraceId, traceId)
                                .orderByAsc(AgentSpan::getId))
                .stream().map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("spanType", s.getSpanType());
                    m.put("name", s.getName());
                    m.put("status", s.getStatus());
                    m.put("inputTokens", s.getInputTokens());
                    m.put("outputTokens", s.getOutputTokens());
                    m.put("latencyMs", s.getLatencyMs());
                    return m;
                }).toList();
    }
}
