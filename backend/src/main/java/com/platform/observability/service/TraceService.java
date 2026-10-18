package com.platform.observability.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.AgentVersion;
import com.platform.agent.mapper.AgentVersionMapper;
import com.platform.capability.runtime.ToolCallEvent;
import com.platform.model.entity.ModelEntity;
import com.platform.billing.service.PricingService;
import com.platform.model.mapper.ModelMapper;
import com.platform.observability.entity.AgentSpan;
import com.platform.observability.entity.AgentTrace;
import com.platform.observability.mapper.AgentSpanMapper;
import com.platform.observability.mapper.AgentTraceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Trace/Span 落库（需求 3.4/3.13、架构 8.2）：一次调用一条 trace（agent 根 span + retrieval span + llm span）。
 * 调试链路不写正式 Trace（需求 3.1.3(10)）；kind 仅 internal/external/eval，来源细分走 tags。
 * 成本口径（需求 3.7）：billing_rule 计费规则（P1）未实现，回落 model 默认定价（元/千 Token），均未配置为 0。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraceService {

    /** kind 取值（需求 3.4 Traces 列表）。 */
    public static final String KIND_INTERNAL = "internal";
    public static final String KIND_EXTERNAL = "external";
    public static final String KIND_EVAL = "eval";

    /** 详情页展示完整内容，仅做防御性截断。 */
    private static final int TEXT_LIMIT = 10_000;
    private static final int ERROR_LIMIT = 1000;

    private final AgentTraceMapper traceMapper;
    private final AgentSpanMapper spanMapper;
    private final AgentVersionMapper versionMapper;
    private final ModelMapper modelMapper;
    private final PricingService pricingService;
    private final ObjectMapper objectMapper;

    /** 扩展元数据：kind/版本/输入输出/错误/标签（均可为空，缺省回落）。 */
    public record TraceExtra(String kind, String version, String inputText, String outputText,
                             String errorMessage, List<String> tags) {

        public static TraceExtra internal(String version, String input, String output) {
            return new TraceExtra(KIND_INTERNAL, version, input, output, null, null);
        }

        public static TraceExtra internalError(String version, String input, String output, String errorMessage) {
            return new TraceExtra(KIND_INTERNAL, version, input, output, errorMessage, null);
        }

        /** 评测调用：version 为空表示编辑态配置（预发布门禁），tags 记录评测类型。 */
        public static TraceExtra eval(String version, String input, String output, List<String> tags) {
            return new TraceExtra(KIND_EVAL, version, input, output, null, tags);
        }
    }

    /** 兼容既有调用：等价于 extra=null（kind=internal、版本自动取最新发布版本）。 */
    public void record(Long tenantId, String traceId, Long conversationId, String sessionId,
                       Long agentId, Long userId, String agentName, String status,
                       String modelName, long inTokens, long outTokens, long latencyMs,
                       int citationCount) {
        record(tenantId, traceId, conversationId, sessionId, agentId, userId, agentName, status,
                modelName, inTokens, outTokens, latencyMs, citationCount, null);
    }

    public void record(Long tenantId, String traceId, Long conversationId, String sessionId,
                       Long agentId, Long userId, String agentName, String status,
                       String modelName, long inTokens, long outTokens, long latencyMs,
                       int citationCount, TraceExtra extra) {
        record(tenantId, traceId, conversationId, sessionId, agentId, userId, agentName, status,
                modelName, inTokens, outTokens, latencyMs, citationCount, extra, null);
    }

    /** 带工具调用事件：工具 Span 与子 Agent chain Span 挂在根 Span 下（需求 3.4 / 3.1.3(8)）。 */
    public void record(Long tenantId, String traceId, Long conversationId, String sessionId,
                       Long agentId, Long userId, String agentName, String status,
                       String modelName, long inTokens, long outTokens, long latencyMs,
                       int citationCount, TraceExtra extra, List<ToolCallEvent> toolCalls) {
        try {
            String kind = extra != null && extra.kind() != null ? extra.kind() : KIND_INTERNAL;
            String version = extra != null && extra.version() != null ? extra.version()
                    : safeLatestVersion(agentId);
            BigDecimal cost = safeCost(modelName, tenantId, inTokens, outTokens);

            AgentTrace t = new AgentTrace();
            t.setTraceId(traceId);
            t.setSessionId(sessionId);
            t.setConversationId(conversationId);
            t.setAgentId(agentId);
            t.setAgentName(agentName);
            t.setVersion(version);
            t.setKind(kind);
            t.setUserId(userId);
            t.setStatus(status);
            t.setModelName(modelName);
            t.setInputText(truncate(extra == null ? null : extra.inputText(), TEXT_LIMIT));
            t.setOutputText(truncate(extra == null ? null : extra.outputText(), TEXT_LIMIT));
            t.setInputTokens(inTokens);
            t.setOutputTokens(outTokens);
            t.setTotalTokens(inTokens + outTokens);
            t.setCost(cost);
            t.setLatencyMs(latencyMs);
            t.setErrorMessage(truncate(extra == null ? null : extra.errorMessage(), ERROR_LIMIT));
            t.setTags(tagsJson(extra));
            t.setTenantId(tenantId);
            t.setCreatedAt(LocalDateTime.now());
            traceMapper.insert(t);

            // Span 树：agent 根 Span（parent=null）+ retrieval + llm，根耗时冗余到 agent_trace.latency_ms（架构 7.1）
            String rootSpanId = newSpanId();
            LocalDateTime end = LocalDateTime.now();
            LocalDateTime start = end.minusNanos(Math.max(latencyMs, 0) * 1_000_000L);

            // 注意：conversationId 在评测/集成链路为 null，Map.of 不允许 null 值（LinkedHashMap 可）
            Map<String, Object> rootAttrs = new LinkedHashMap<>();
            rootAttrs.put("conversationId", conversationId);
            rootAttrs.put("kind", kind);
            AgentSpan root = newSpan(tenantId, traceId, rootSpanId, null, agentId, "agent",
                    invokeName(agentId, agentName), status, start, end, latencyMs);
            root.setAttributes(json(rootAttrs));
            spanMapper.insert(root);

            if (citationCount > 0) {
                AgentSpan retrieval = newSpan(tenantId, traceId, newSpanId(), rootSpanId, agentId,
                        "retrieval", "knowledge-retrieval", "SUCCESS", start, end, 0);
                retrieval.setAttributes(json(Map.of("hits", citationCount)));
                spanMapper.insert(retrieval);
            }

            AgentSpan llm = newSpan(tenantId, traceId, newSpanId(), rootSpanId, agentId,
                    "llm", modelName == null ? "llm" : modelName, status, start, end, latencyMs);
            llm.setModelName(modelName);
            llm.setInputTokens(inTokens);
            llm.setOutputTokens(outTokens);
            llm.setAttributes(json(Map.of()));
            spanMapper.insert(llm);

            if (toolCalls != null) {
                for (ToolCallEvent tc : toolCalls) {
                    LocalDateTime toolEnd = LocalDateTime.now();
                    LocalDateTime toolStart = toolEnd.minusNanos(Math.max(tc.durationMs(), 0) * 1_000_000L);
                    AgentSpan toolSpan = newSpan(tenantId, traceId, newSpanId(), rootSpanId, agentId,
                            "tool", tc.displayName() == null ? tc.toolName() : tc.displayName(),
                            tc.status(), toolStart, toolEnd, tc.durationMs());
                    toolSpan.setToolName(tc.toolName());
                    Map<String, Object> attrs = new LinkedHashMap<>();
                    attrs.put("toolType", tc.toolType());
                    attrs.put("input", truncate(tc.input(), TEXT_LIMIT));
                    attrs.put("output", truncate(tc.output(), TEXT_LIMIT));
                    if (tc.subAgentId() != null) {
                        attrs.put("subAgentId", tc.subAgentId());
                        attrs.put("subAgentName", tc.subAgentName());
                        attrs.put("inputTokens", tc.inputTokens());
                        attrs.put("outputTokens", tc.outputTokens());
                    }
                    toolSpan.setAttributes(json(attrs));
                    spanMapper.insert(toolSpan);

                    if (tc.subAgentId() != null) {
                        AgentSpan chain = newSpan(tenantId, traceId, newSpanId(), toolSpan.getSpanId(), agentId,
                                "chain", invokeName(tc.subAgentId(), tc.subAgentName()), tc.status(),
                                toolStart, toolEnd, tc.durationMs());
                        chain.setAttributes(json(Map.of("subAgentId", tc.subAgentId(),
                                "inputTokens", tc.inputTokens(), "outputTokens", tc.outputTokens())));
                        spanMapper.insert(chain);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Trace 落库失败 traceId={}", traceId, e);
        }
    }

    /** Trace 名称（需求 3.4 Traces 列表 NAME 列）：invoke_agent_{id}#{name}。 */
    public static String invokeName(Long agentId, String agentName) {
        return "invoke_agent_" + (agentId == null ? "0" : agentId) + "#"
                + (agentName == null || agentName.isBlank() ? "agent" : agentName);
    }

    private AgentSpan newSpan(Long tenantId, String traceId, String spanId, String parentSpanId, Long agentId,
                              String type, String name, String status,
                              LocalDateTime start, LocalDateTime end, long durationMs) {
        AgentSpan s = new AgentSpan();
        s.setTraceId(traceId);
        s.setSpanId(spanId);
        s.setParentSpanId(parentSpanId);
        s.setAgentId(agentId);
        s.setSpanType(type);
        s.setName(name);
        s.setStatus(status);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setDurationMs(durationMs);
        s.setLatencyMs(durationMs);   // 兼容旧列
        s.setInputTokens(0L);
        s.setOutputTokens(0L);
        s.setTenantId(tenantId);
        s.setCreatedAt(LocalDateTime.now());
        return s;
    }

    /** Span 视图（需求 7.3）：前端按类型着色（agent 蓝/chain 绿/llm 橙/tool 灰，架构 4.5）。 */
    public List<Map<String, Object>> spansOf(String traceId) {
        return spanMapper.selectList(
                        new LambdaQueryWrapper<AgentSpan>()
                                .eq(AgentSpan::getTraceId, traceId)
                                .orderByAsc(AgentSpan::getId))
                .stream().map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("spanId", s.getSpanId());
                    m.put("parentSpanId", s.getParentSpanId());
                    m.put("spanType", s.getSpanType());
                    m.put("name", s.getName());
                    m.put("status", s.getStatus());
                    m.put("startTime", s.getStartTime() != null ? s.getStartTime() : s.getCreatedAt());
                    m.put("endTime", s.getEndTime());
                    m.put("durationMs", s.getDurationMs() != null ? s.getDurationMs() : s.getLatencyMs());
                    m.put("modelName", s.getModelName());
                    m.put("toolName", s.getToolName());
                    m.put("inputTokens", s.getInputTokens());
                    m.put("outputTokens", s.getOutputTokens());
                    m.put("attributes", parseJson(s.getAttributes()));
                    return m;
                }).toList();
    }

    // ---------- 辅助 ----------

    private String safeLatestVersion(Long agentId) {
        try {
            return latestVersion(agentId);
        } catch (Exception e) {
            return null;
        }
    }

    private String latestVersion(Long agentId) {
        if (agentId == null) return null;
        AgentVersion v = versionMapper.selectList(new LambdaQueryWrapper<AgentVersion>()
                        .eq(AgentVersion::getAgentId, agentId)
                        .orderByDesc(AgentVersion::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        return v == null ? null : v.getVersion();
    }

    /** 成本 = 输入 Token/1000 × 输入单价 + 输出 Token/1000 × 输出单价（model 表单价为元/千 Token）。 */
    private BigDecimal safeCost(String modelName, Long tenantId, long inTokens, long outTokens) {
        try {
            if (modelName == null || (inTokens == 0 && outTokens == 0)) return BigDecimal.ZERO;
            return pricingService.cost(tenantId, modelName, inTokens, outTokens);
        } catch (Exception e) {
            log.debug("Trace 成本计算失败 model={}：{}", modelName, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    private String tagsJson(TraceExtra extra) {
        if (extra == null || extra.tags() == null || extra.tags().isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(extra.tags());
        } catch (Exception e) {
            return null;
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String text) {
        if (text == null || text.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(text, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String truncate(String text, int limit) {
        if (text == null) return null;
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private String newSpanId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
