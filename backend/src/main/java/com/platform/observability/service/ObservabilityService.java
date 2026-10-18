package com.platform.observability.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.common.exception.BusinessException;
import com.platform.observability.entity.AgentTrace;
import com.platform.observability.mapper.AgentTraceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可观测查询（需求 3.13/架构 4.5 Observability）：指标卡、Trace 列表、Span 树、模型分布。
 */
@Service
@RequiredArgsConstructor
public class ObservabilityService {

    private final AgentTraceMapper traceMapper;
    private final TraceService traceService;

    public Map<String, Object> overview(Long agentId, int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(Math.min(Math.max(days, 1), 365));
        List<AgentTrace> traces = tracesOf(agentId, since);

        long success = traces.stream().filter(t -> "SUCCESS".equals(t.getStatus())).count();
        long totalTokens = traces.stream()
                .mapToLong(t -> safe(t.getInputTokens()) + safe(t.getOutputTokens())).sum();
        long avgLatency = traces.isEmpty() ? 0
                : traces.stream().mapToLong(t -> safe(t.getLatencyMs())).sum() / traces.size();

        Map<String, long[]> byModel = new LinkedHashMap<>();
        for (AgentTrace t : traces) {
            String key = t.getModelName() == null ? "-" : t.getModelName();
            byModel.computeIfAbsent(key, k -> new long[2])[0] += safe(t.getInputTokens()) + safe(t.getOutputTokens());
            byModel.get(key)[1] += 1;
        }
        List<Map<String, Object>> modelDist = byModel.entrySet().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("modelName", e.getKey());
            m.put("tokens", e.getValue()[0]);
            m.put("count", e.getValue()[1]);
            return m;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceCount", traces.size());
        result.put("successRate", traces.isEmpty() ? 0 : Math.round(success * 1000.0 / traces.size()) / 10.0);
        result.put("totalTokens", totalTokens);
        result.put("avgLatencyMs", avgLatency);
        result.put("byModel", modelDist);
        return result;
    }

    public Map<String, Object> traces(Long agentId, long page, long pageSize) {
        Page<AgentTrace> result = traceMapper.selectPage(new Page<>(page, pageSize),
                baseQuery(agentId).orderByDesc(AgentTrace::getId));
        List<Map<String, Object>> list = result.getRecords().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("traceId", t.getTraceId());
            m.put("agentId", t.getAgentId());
            m.put("conversationId", t.getConversationId());
            m.put("status", t.getStatus());
            m.put("modelName", t.getModelName());
            m.put("inputTokens", t.getInputTokens());
            m.put("outputTokens", t.getOutputTokens());
            m.put("latencyMs", t.getLatencyMs());
            m.put("score", t.getScore());
            m.put("createdAt", t.getCreatedAt());
            return m;
        }).toList();
        return Map.of("list", list, "total", result.getTotal(), "page", page, "pageSize", pageSize);
    }

    public Map<String, Object> traceDetail(String traceId) {
        AgentTrace t = traceMapper.selectOne(new LambdaQueryWrapper<AgentTrace>()
                .eq(AgentTrace::getTraceId, traceId));
        if (t == null) throw new BusinessException(404, "Trace 不存在");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("traceId", t.getTraceId());
        m.put("conversationId", t.getConversationId());
        m.put("agentId", t.getAgentId());
        m.put("status", t.getStatus());
        m.put("modelName", t.getModelName());
        m.put("inputTokens", t.getInputTokens());
        m.put("outputTokens", t.getOutputTokens());
        m.put("latencyMs", t.getLatencyMs());
        m.put("createdAt", t.getCreatedAt());
        m.put("spans", traceService.spansOf(traceId));
        return m;
    }

    private LambdaQueryWrapper<AgentTrace> baseQuery(Long agentId) {
        return new LambdaQueryWrapper<AgentTrace>()
                .eq(agentId != null, AgentTrace::getAgentId, agentId);
    }

    private List<AgentTrace> tracesOf(Long agentId, LocalDateTime since) {
        return traceMapper.selectList(baseQuery(agentId).ge(AgentTrace::getCreatedAt, since));
    }

    private long safe(Long v) {
        return v == null ? 0 : v;
    }
}
