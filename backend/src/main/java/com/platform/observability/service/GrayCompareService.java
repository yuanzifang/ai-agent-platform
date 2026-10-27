package com.platform.observability.service;

import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 灰度 A/B 对比与轨迹分析（需求：模型切换后按灰度标记对比）。
 *
 * <p>分组语义：gray 组 = agent_trace.tags 含 {@code gray:<tag>}（灰度标记经 X-Gray-Tag 入口透传写入）；
 * baseline 组 = 同窗口内不含该标记的流量。指标：请求量/错误率/延迟均值与 P95/token/成本，
 * 轨迹分析（按模型与 kind 的分组统计、工具调用强度）与轨迹评测（eval_result 按评测任务 gray_tag 分组的得分/通过率）。</p>
 *
 * <p>租户边界：一律取 TenantContext 当前租户，禁绝跨租户聚合。</p>
 */
@Slf4j
@Service
public class GrayCompareService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${gray.compare.max-days:90}")
    private int maxDays = 90;

    public GrayCompareService(@org.springframework.beans.factory.annotation.Qualifier("jdbcTemplate")
                              JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 清洗规则与入口一致（ChatStreamService.sanitizeGrayTag），此处独立防跨模块耦合。 */
    static String sanitize(String raw) {
        if (raw == null) return null;
        String stripped = raw.trim().replaceAll("[^A-Za-z0-9._:-]", "");
        if (stripped.isEmpty()) return null;
        return stripped.length() > 64 ? stripped.substring(0, 64) : stripped;
    }

    public Map<String, Object> compare(String tag, int days, Long agentId) {
        String t = sanitize(tag);
        if (t == null) throw new BusinessException(400, "灰度标记不能为空（仅限字母数字._:-）");
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) throw new BusinessException(403, "缺少租户上下文");
        int d = Math.min(Math.max(days, 1), Math.max(1, maxDays));
        LocalDateTime start = LocalDateTime.now().minusDays(d);

        String grayCond = "JSON_CONTAINS(tags, JSON_QUOTE('gray:" + t + "'))";
        String baseCond = "NOT JSON_CONTAINS(tags, JSON_QUOTE('gray:" + t + "'))";

        Map<String, Object> baseline = groupStats(baseCond, tenantId, start, agentId);
        Map<String, Object> gray = groupStats(grayCond, tenantId, start, agentId);
        baseline.put("p95LatencyMs", p95(baseCond, tenantId, start, agentId));
        gray.put("p95LatencyMs", p95(grayCond, tenantId, start, agentId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tag", t);
        result.put("days", d);
        result.put("baseline", baseline);
        result.put("gray", gray);
        result.put("delta", delta(baseline, gray));

        // 轨迹分析：按模型/kind 的分组统计 + 工具调用强度
        baseline.put("byModel", byModel(baseCond, tenantId, start, agentId));
        gray.put("byModel", byModel(grayCond, tenantId, start, agentId));
        baseline.put("byKind", byKind(baseCond, tenantId, start, agentId));
        gray.put("byKind", byKind(grayCond, tenantId, start, agentId));
        baseline.put("avgToolCallsPerTrace", toolCallsPerTrace(baseCond, tenantId, start, agentId));
        gray.put("avgToolCallsPerTrace", toolCallsPerTrace(grayCond, tenantId, start, agentId));

        // 轨迹评测：eval_result 按评测任务 gray_tag 分组（任务建任务时打标）
        result.put("eval", Map.of(
                "baseline", evalStats(tenantId, start, agentId, true, t),
                "gray", evalStats(tenantId, start, agentId, false, t)));
        return result;
    }

    // ---------- 分组聚合 ----------

    private Map<String, Object> groupStats(String cond, Long tenantId, LocalDateTime start, Long agentId) {
        String sql = ("SELECT COUNT(*) requests, "
                + "COALESCE(SUM(status <> 'SUCCESS'), 0) errors, "
                + "COALESCE(AVG(latency_ms), 0) avg_latency, "
                + "COALESCE(AVG(input_tokens), 0) avg_input_tokens, "
                + "COALESCE(AVG(output_tokens), 0) avg_output_tokens, "
                + "COALESCE(AVG(total_tokens), 0) avg_total_tokens, "
                + "COALESCE(SUM(cost), 0) total_cost, "
                + "COALESCE(AVG(cost), 0) avg_cost "
                + "FROM agent_trace WHERE tenant_id = ? AND created_at >= ? "
                + (agentId == null ? "" : "AND agent_id = ? ")
                + "AND " + cond).strip();
        Object[] args = agentId == null
                ? new Object[]{tenantId, start}
                : new Object[]{tenantId, start, agentId};
        Map<String, Object> row = jdbcTemplate.queryForMap(sql, args);
        long requests = ((Number) row.get("requests")).longValue();
        long errors = ((Number) row.get("errors")).longValue();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("requests", requests);
        stats.put("errors", errors);
        stats.put("errorRate", requests == 0 ? 0.0 : round(errors * 1.0 / requests, 4));
        stats.put("avgLatencyMs", round(num(row.get("avg_latency")), 1));
        stats.put("avgInputTokens", round(num(row.get("avg_input_tokens")), 1));
        stats.put("avgOutputTokens", round(num(row.get("avg_output_tokens")), 1));
        stats.put("avgTotalTokens", round(num(row.get("avg_total_tokens")), 1));
        stats.put("totalCost", round(num(row.get("total_cost")), 6));
        stats.put("avgCost", round(num(row.get("avg_cost")), 6));
        return stats;
    }

    private long p95(String cond, Long tenantId, LocalDateTime start, Long agentId) {
        String sql = ("SELECT COALESCE(MAX(latency_ms), 0) FROM ("
                + "SELECT latency_ms, PERCENT_RANK() OVER (ORDER BY latency_ms) pr "
                + "FROM agent_trace WHERE tenant_id = ? AND created_at >= ? "
                + (agentId == null ? "" : "AND agent_id = ? ")
                + "AND " + cond + ") x WHERE pr <= 0.95").strip();
        Object[] args = agentId == null
                ? new Object[]{tenantId, start}
                : new Object[]{tenantId, start, agentId};
        Number v = jdbcTemplate.queryForObject(sql, Number.class, args);
        return v == null ? 0 : v.longValue();
    }

    private List<Map<String, Object>> byModel(String cond, Long tenantId, LocalDateTime start, Long agentId) {
        String sql = ("SELECT COALESCE(model_name, '-') model_name, COUNT(*) requests, "
                + "COALESCE(AVG(latency_ms), 0) avg_latency, "
                + "COALESCE(SUM(status <> 'SUCCESS'), 0) errors "
                + "FROM agent_trace WHERE tenant_id = ? AND created_at >= ? "
                + (agentId == null ? "" : "AND agent_id = ? ")
                + "AND " + cond + " GROUP BY model_name ORDER BY requests DESC LIMIT 10").strip();
        Object[] args = agentId == null
                ? new Object[]{tenantId, start}
                : new Object[]{tenantId, start, agentId};
        return queryRounded(sql, args);
    }

    private List<Map<String, Object>> byKind(String cond, Long tenantId, LocalDateTime start, Long agentId) {
        String sql = ("SELECT COALESCE(kind, 'internal') kind, COUNT(*) requests, "
                + "COALESCE(AVG(latency_ms), 0) avg_latency "
                + "FROM agent_trace WHERE tenant_id = ? AND created_at >= ? "
                + (agentId == null ? "" : "AND agent_id = ? ")
                + "AND " + cond + " GROUP BY kind ORDER BY requests DESC LIMIT 10").strip();
        Object[] args = agentId == null
                ? new Object[]{tenantId, start}
                : new Object[]{tenantId, start, agentId};
        return queryRounded(sql, args);
    }

    private double toolCallsPerTrace(String cond, Long tenantId, LocalDateTime start, Long agentId) {
        String sql = ("SELECT COALESCE(SUM(tool_spans), 0) tools, COALESCE(COUNT(DISTINCT t.trace_id), 0) traces "
                + "FROM agent_trace t LEFT JOIN agent_span s "
                + "ON s.trace_id = t.trace_id AND s.tenant_id = t.tenant_id AND s.span_type = 'tool' "
                + "WHERE t.tenant_id = ? AND t.created_at >= ? "
                + (agentId == null ? "" : "AND t.agent_id = ? ")
                + "AND " + cond.replace("tags", "t.tags")).strip();
        Object[] args = agentId == null
                ? new Object[]{tenantId, start}
                : new Object[]{tenantId, start, agentId};
        Map<String, Object> row = jdbcTemplate.queryForMap(sql, args);
        long traces = ((Number) row.get("traces")).longValue();
        return traces == 0 ? 0.0 : round(num(row.get("tools")) / traces, 2);
    }

    private List<Map<String, Object>> queryRounded(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.replaceAll((k, v) -> v instanceof Number n && (k.contains("avg") || k.contains("_latency"))
                    ? round(num(v), 1) : v);
            out.add(copy);
        }
        return out;
    }

    private Map<String, Object> evalStats(Long tenantId, LocalDateTime start, Long agentId, boolean baseline, String tag) {
        String cond = baseline
                ? "(gray_tag IS NULL OR gray_tag <> ?)"
                : "gray_tag = ?";
        String sql = ("SELECT COUNT(*) evals, COALESCE(AVG(score), 0) avg_score, "
                + "COALESCE(SUM(passed), 0) passed, COALESCE(SUM(score IS NULL), 0) unscored "
                + "FROM eval_result WHERE tenant_id = ? AND created_at >= ? AND task_id IN ("
                + "SELECT id FROM eval_task WHERE tenant_id = ? AND created_at >= ? AND " + cond + ")"
                ).strip();
        List<Object> args = new ArrayList<>(List.of(tenantId, start, tenantId, start, tag));
        Map<String, Object> row = jdbcTemplate.queryForMap(sql, args.toArray());
        long evals = ((Number) row.get("evals")).longValue();
        long passed = ((Number) row.get("passed")).longValue();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("evals", evals);
        stats.put("avgScore", round(num(row.get("avg_score")), 4));
        stats.put("passRate", evals == 0 ? 0.0 : round(passed * 1.0 / evals, 4));
        return stats;
    }

    /** delta = gray - baseline（仅对可做差的关键指标）。 */
    Map<String, Object> delta(Map<String, Object> baseline, Map<String, Object> gray) {
        Map<String, Object> d = new LinkedHashMap<>();
        for (String key : List.of("requests", "errors", "errorRate", "avgLatencyMs",
                "avgTotalTokens", "totalCost", "avgCost", "p95LatencyMs")) {
            Object b = baseline.get(key);
            Object g = gray.get(key);
            if (b instanceof Number bn && g instanceof Number gn) {
                d.put(key, round(gn.doubleValue() - bn.doubleValue(), 4));
            }
        }
        return d;
    }

    private static double num(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static double round(double v, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(v * factor) / factor;
    }
}
