package com.platform.observability.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.common.exception.BusinessException;
import com.platform.observability.entity.AgentTrace;
import com.platform.observability.mapper.AgentSpanMapper;
import com.platform.observability.mapper.AgentTraceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可观测查询（需求 3.4/7.3）：
 * 指标卡（成本/追踪数/Token/Span/P50/P99/错误数）、Span 结构图、Top Tools、模型占比、
 * Trace 高级筛选列表、Span 树详情、Sessions/Users/Versions 聚合。
 * 多租户条件由 TenantLineInnerInterceptor 自动追加。
 */
@Service
@RequiredArgsConstructor
public class ObservabilityService {

    private static final int LIST_TEXT_LIMIT = 200;
    private static final int MAX_PAGE_SIZE = 100;
    private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final AgentTraceMapper traceMapper;
    private final AgentSpanMapper spanMapper;
    private final TraceService traceService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** 顶部指标卡 + Span 结构图 + Top Tools + 模型占比。 */
    public Map<String, Object> overview(Long agentId, int days) {
        int d = Math.min(Math.max(days, 1), 90);
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusDays(d);
        boolean hourly = d <= 2;
        String fmt = hourly ? "%Y-%m-%d %H:00" : "%Y-%m-%d";

        List<AgentTrace> traces = traceMapper.selectList(new LambdaQueryWrapper<AgentTrace>()
                .select(AgentTrace::getStatus, AgentTrace::getLatencyMs, AgentTrace::getTotalTokens, AgentTrace::getCost)
                .eq(agentId != null, AgentTrace::getAgentId, agentId)
                .ge(AgentTrace::getCreatedAt, start));

        BigDecimal cost = traces.stream()
                .map(t -> t.getCost() == null ? BigDecimal.ZERO : t.getCost())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long errorCount = traces.stream().filter(t -> "ERROR".equals(t.getStatus())).count();
        long totalTokens = traces.stream().mapToLong(t -> safe(t.getTotalTokens())).sum();
        List<Long> latencies = traces.stream().map(t -> safe(t.getLatencyMs())).toList();

        Map<String, Long> byType = new LinkedHashMap<>();
        for (Map<String, Object> row : spanMapper.countByType(agentId, start, end)) {
            byType.put(str(row.get("spanType")), num(row.get("cnt")));
        }
        long spanCount = byType.values().stream().mapToLong(Long::longValue).sum();

        // Span 结构图：按时间桶 × 四维度堆叠（retrieval 归入 tool 维度）
        Map<String, Map<String, Long>> series = new LinkedHashMap<>();
        for (Map<String, Object> row : spanMapper.seriesByType(agentId, start, end, fmt)) {
            series.computeIfAbsent(str(row.get("bucket")), k -> new LinkedHashMap<>())
                    .merge(chartDim(str(row.get("spanType"))), num(row.get("cnt")), Long::sum);
        }
        List<Map<String, Object>> spanSeries = new ArrayList<>();
        for (String bucket : timeBuckets(start, end, hourly)) {
            Map<String, Long> byDim = series.getOrDefault(bucket, Map.of());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("bucket", bucket);
            item.put("agent", byDim.getOrDefault("agent", 0L));
            item.put("chain", byDim.getOrDefault("chain", 0L));
            item.put("llm", byDim.getOrDefault("llm", 0L));
            item.put("tool", byDim.getOrDefault("tool", 0L));
            spanSeries.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cost", cost);
        result.put("traceCount", traces.size());
        result.put("totalTokens", totalTokens);
        result.put("spanCount", spanCount);
        result.put("latencyP50Ms", percentile(latencies, 50));
        result.put("latencyP99Ms", percentile(latencies, 99));
        result.put("errorCount", errorCount);
        result.put("byModel", spanMapper.modelShare(agentId, start, end));
        result.put("topTools", topTools(agentId, start, end));
        result.put("spanSeries", spanSeries);
        return result;
    }

    /** Traces 列表：高级筛选 + 分页（需求 7.3 /observability/traces）。 */
    public Map<String, Object> traces(AgentTraceQuery q) {
        long size = clampPageSize(q.pageSize());
        long page = Math.max(q.page(), 1);

        LambdaQueryWrapper<AgentTrace> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(q.agentId() != null, AgentTrace::getAgentId, q.agentId())
                .eq(q.status() != null && !q.status().isBlank(), AgentTrace::getStatus, upper(q.status()))
                .eq(q.kind() != null && !q.kind().isBlank(), AgentTrace::getKind, lower(q.kind()))
                .eq(q.version() != null && !q.version().isBlank(), AgentTrace::getVersion, q.version())
                .in(q.userIds() != null && !q.userIds().isEmpty(), AgentTrace::getUserId, q.userIds())
                .in(q.sessionIds() != null && !q.sessionIds().isEmpty(), AgentTrace::getSessionId, q.sessionIds())
                .ge(q.tokenMin() != null, AgentTrace::getTotalTokens, q.tokenMin())
                .le(q.tokenMax() != null, AgentTrace::getTotalTokens, q.tokenMax())
                .ge(q.latencyMin() != null, AgentTrace::getLatencyMs, q.latencyMin())
                .le(q.latencyMax() != null, AgentTrace::getLatencyMs, q.latencyMax())
                .ge(q.startTime() != null, AgentTrace::getCreatedAt, q.startTime())
                .le(q.endTime() != null, AgentTrace::getCreatedAt, q.endTime());
        if (q.hideEval()) wrapper.ne(AgentTrace::getKind, TraceService.KIND_EVAL);
        if (q.keyword() != null && !q.keyword().isBlank()) {
            String kw = q.keyword().trim();
            wrapper.and(w -> w.like(AgentTrace::getTraceId, kw)
                    .or().like(AgentTrace::getInputText, kw)
                    .or().like(AgentTrace::getOutputText, kw));
        }
        if (q.tags() != null && !q.tags().isEmpty()) {
            wrapper.and(w -> {
                boolean first = true;
                for (String tag : q.tags()) {
                    if (tag == null || tag.isBlank()) continue;
                    if (!first) w.or();
                    w.apply("JSON_CONTAINS(tags, {0})", "\"" + tag.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
                    first = false;
                }
            });
        }
        wrapper.orderByDesc(AgentTrace::getId);

        Page<AgentTrace> result = traceMapper.selectPage(new Page<>(page, size), wrapper);
        List<Map<String, Object>> list = result.getRecords().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("traceId", t.getTraceId());
            m.put("kind", t.getKind());
            m.put("name", TraceService.invokeName(t.getAgentId(), t.getAgentName()));
            m.put("agentName", t.getAgentName());
            m.put("version", t.getVersion());
            m.put("input", truncate(t.getInputText(), LIST_TEXT_LIMIT));
            m.put("output", truncate(t.getOutputText(), LIST_TEXT_LIMIT));
            m.put("status", t.getStatus());
            m.put("startTime", t.getCreatedAt());
            m.put("latencyMs", t.getLatencyMs());
            m.put("totalTokens", t.getTotalTokens());
            m.put("score", t.getScore());
            return m;
        }).toList();
        return Map.of("list", list, "total", result.getTotal(), "page", page, "pageSize", size);
    }

    /** Trace 详情：字段全集 + Span 树（需求 7.3 /observability/traces/{traceId}）。 */
    public Map<String, Object> traceDetail(String traceId) {
        AgentTrace t = traceMapper.selectOne(new LambdaQueryWrapper<AgentTrace>()
                .eq(AgentTrace::getTraceId, traceId));
        if (t == null) throw new BusinessException(404, "Trace 不存在");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("traceId", t.getTraceId());
        m.put("kind", t.getKind());
        m.put("name", TraceService.invokeName(t.getAgentId(), t.getAgentName()));
        m.put("agentId", t.getAgentId());
        m.put("agentName", t.getAgentName());
        m.put("version", t.getVersion());
        m.put("conversationId", t.getConversationId());
        m.put("sessionId", t.getSessionId());
        m.put("userId", t.getUserId());
        m.put("status", t.getStatus());
        m.put("modelName", t.getModelName());
        m.put("input", t.getInputText());
        m.put("output", t.getOutputText());
        m.put("inputTokens", t.getInputTokens());
        m.put("outputTokens", t.getOutputTokens());
        m.put("totalTokens", t.getTotalTokens());
        m.put("cost", t.getCost());
        m.put("latencyMs", t.getLatencyMs());
        m.put("errorMessage", t.getErrorMessage());
        m.put("score", t.getScore());
        m.put("tags", parseTags(t.getTags()));
        m.put("startTime", t.getCreatedAt());
        m.put("spans", traceService.spansOf(traceId));
        return m;
    }

    // ---------- Sessions / Users / Versions Tab（需求 3.4.3(6)） ----------

    public Map<String, Object> sessions(Long agentId, LocalDateTime startTime, LocalDateTime endTime,
                                        String keyword, long page, long pageSize) {
        long size = clampPageSize(pageSize);
        long p = Math.max(page, 1);
        String kw = blankToNull(keyword);
        List<Map<String, Object>> list = traceMapper.sessionAgg(agentId, startTime, endTime, kw,
                (p - 1) * size, size);
        long total = traceMapper.sessionAggCount(agentId, startTime, endTime, kw);
        return Map.of("list", list, "total", total, "page", p, "pageSize", size);
    }

    public Map<String, Object> users(Long agentId, LocalDateTime startTime, LocalDateTime endTime,
                                     String keyword, long page, long pageSize) {
        long size = clampPageSize(pageSize);
        long p = Math.max(page, 1);
        String kw = blankToNull(keyword);
        Long kwUserId = null;
        if (kw != null && kw.matches("\\d+")) {
            try {
                kwUserId = Long.parseLong(kw);
            } catch (NumberFormatException ignored) {
            }
        }
        List<Map<String, Object>> list = traceMapper.userAgg(agentId, startTime, endTime, kw, kwUserId,
                (p - 1) * size, size);
        long total = traceMapper.userAggCount(agentId, startTime, endTime, kw, kwUserId);
        return Map.of("list", list, "total", total, "page", p, "pageSize", size);
    }

    public Map<String, Object> versions(Long agentId, LocalDateTime startTime, LocalDateTime endTime,
                                        String keyword, long page, long pageSize) {
        long size = clampPageSize(pageSize);
        long p = Math.max(page, 1);
        String kw = blankToNull(keyword);
        List<Map<String, Object>> list = traceMapper.versionAgg(agentId, startTime, endTime, kw,
                (p - 1) * size, size).stream().map(m -> {
            Map<String, Object> r = new LinkedHashMap<>(m);
            long calls = num(m.get("calls"));
            long successCount = num(m.get("successCount"));
            r.put("successRate", calls == 0 ? 0 : Math.round(successCount * 1000.0 / calls) / 10.0);
            r.remove("successCount");
            return r;
        }).toList();
        long total = traceMapper.versionAggCount(agentId, startTime, endTime, kw);
        return Map.of("list", list, "total", total, "page", p, "pageSize", size);
    }

    // ---------- 辅助 ----------

    /** Top Tools：按调用次数倒序，附 P95 延迟（需求 3.4.3(4)）。 */
    private List<Map<String, Object>> topTools(Long agentId, LocalDateTime start, LocalDateTime end) {
        Map<String, List<Long>> byTool = new LinkedHashMap<>();
        for (Map<String, Object> row : spanMapper.toolCalls(agentId, start, end)) {
            byTool.computeIfAbsent(str(row.get("toolName")), k -> new ArrayList<>())
                    .add(num(row.get("durationMs")));
        }
        return byTool.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .limit(10)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("toolName", e.getKey().isBlank() ? "未知工具" : e.getKey());
                    m.put("calls", e.getValue().size());
                    m.put("p95LatencyMs", percentile(e.getValue(), 95));
                    return m;
                }).toList();
    }

    /** Span 结构图四维度归并：retrieval 计入 tool（架构 4.5 仅四色）。 */
    private String chartDim(String spanType) {
        if ("retrieval".equals(spanType)) return "tool";
        if ("agent".equals(spanType) || "chain".equals(spanType) || "llm".equals(spanType) || "tool".equals(spanType)) {
            return spanType;
        }
        return "tool";
    }

    static List<String> timeBuckets(LocalDateTime start, LocalDateTime end, boolean hourly) {
        List<String> list = new ArrayList<>();
        DateTimeFormatter fmt = hourly ? HOUR_FMT : DAY_FMT;
        LocalDateTime cursor = hourly
                ? start.withMinute(0).withSecond(0).withNano(0)
                : start.toLocalDate().atStartOfDay();
        while (!cursor.isAfter(end)) {
            list.add(cursor.format(fmt));
            cursor = hourly ? cursor.plusHours(1) : cursor.plusDays(1);
        }
        return list;
    }

    /** 应用层分位数（近似算法：nearest-rank，需求 3.3/3.4）。 */
    public static long percentile(List<Long> values, int p) {
        if (values == null || values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) return List.of();
        try {
            return objectMapper.readValue(tagsJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static long clampPageSize(long pageSize) {
        return Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
    }

    private static long safe(Long v) {
        return v == null ? 0 : v;
    }

    private static long num(Object v) {
        return v == null ? 0 : ((Number) v).longValue();
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String truncate(String text, int limit) {
        if (text == null) return null;
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase();
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase();
    }

    /** Traces 列表查询参数（需求 7.3）。 */
    public record AgentTraceQuery(Long agentId, String status, String kind, String version,
                                  List<String> tags, List<Long> userIds, List<String> sessionIds,
                                  Long tokenMin, Long tokenMax, Long latencyMin, Long latencyMax,
                                  LocalDateTime startTime, LocalDateTime endTime, String keyword,
                                  boolean hideEval, long page, long pageSize) {
    }
}
