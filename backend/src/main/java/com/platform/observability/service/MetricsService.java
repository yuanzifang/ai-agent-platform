package com.platform.observability.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.exception.BusinessException;
import com.platform.common.util.TimeFormat;
import com.platform.observability.entity.AgentMetricsDaily;
import com.platform.observability.mapper.AgentMetricsDailyMapper;
import com.platform.observability.mapper.AgentTraceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 监控统计（需求 3.3 / 7.3 /metrics/agent/{id}）：
 * 指标卡（含环比）+ 活跃用户/调用/错误趋势。
 * 口径（架构 7.3）：可加性指标（成本/调用量/Token/错误数）按日与 agent_metrics_daily 合并，
 * 日表缺失日与当日回落实时明细；对话数/活跃用户为期间去重指标，从明细实时计算；P99 应用层分位数。
 */
@Service
@RequiredArgsConstructor
public class MetricsService {

    private final AgentTraceMapper traceMapper;
    private final AgentMetricsDailyMapper dailyMapper;

    public Map<String, Object> agentMetrics(Long agentId, String period, String startTime, String endTime) {
        LocalDateTime now = LocalDateTime.now();
        String key = period == null || period.isBlank() ? "24h" : period.trim().toLowerCase();
        LocalDateTime start;
        LocalDateTime end;
        switch (key) {
            case "7d" -> {
                end = now;
                start = now.minusDays(7);
            }
            case "30d" -> {
                end = now;
                start = now.minusDays(30);
            }
            case "custom" -> {
                start = TimeFormat.parseLenient(startTime, false);
                end = TimeFormat.parseLenient(endTime, true);
                if (start == null || end == null) {
                    throw new BusinessException(400, "自定义时间范围需提供 startTime 与 endTime");
                }
                if (!start.isBefore(end)) {
                    throw new BusinessException(400, "开始时间必须早于结束时间");
                }
            }
            default -> {
                key = "24h";
                end = now;
                start = now.minusHours(24);
            }
        }
        Duration span = Duration.between(start, end);
        if (span.toDays() > 90) {
            throw new BusinessException(400, "时间范围上限 90 天");
        }
        LocalDateTime prevStart = start.minus(span);

        Agg cur = compute(agentId, start, end);
        Agg prev = compute(agentId, prevStart, start);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("cost", metric(round(cur.cost().doubleValue(), 6), round(prev.cost().doubleValue(), 6)));
        metrics.put("sessions", metric(cur.sessions(), prev.sessions()));
        metrics.put("dau", metric(cur.dau(), prev.dau()));
        metrics.put("sessionsPerUser", metric(cur.sessionsPerUser(), prev.sessionsPerUser()));
        metrics.put("calls", metric(cur.calls(), prev.calls()));
        metrics.put("successRate", metric(cur.successRate(), prev.successRate()));
        metrics.put("p99LatencyMs", metric(cur.p99(), prev.p99()));
        metrics.put("tokens", metric(cur.tokens(), prev.tokens()));
        metrics.put("errorCount", metric(cur.errorCount(), prev.errorCount()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", key);
        result.put("startTime", start);
        result.put("endTime", end);
        result.put("metrics", metrics);
        result.put("trends", trends(agentId, start, end));
        return result;
    }

    /** 指标项：value、prev、环比 changeRatio(%)，上一周期为 0 时显示 0%（需求 3.3.3(1)）。 */
    private Map<String, Object> metric(long value, long prev) {
        return metric((double) value, (double) prev);
    }

    private Map<String, Object> metric(double value, double prev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value);
        m.put("prev", prev);
        m.put("changeRatio", prev == 0 ? 0 : Math.round((value - prev) / prev * 1000) / 10.0);
        return m;
    }

    // ---------- 聚合 ----------

    private Agg compute(Long agentId, LocalDateTime start, LocalDateTime end) {
        Map<String, Object> distinct = traceMapper.distinctAgg(agentId, start, end);
        long sessions = distinct == null ? 0 : num(distinct.get("sessions"));
        long users = distinct == null ? 0 : num(distinct.get("users"));

        Map<LocalDate, AgentMetricsDaily> daily = loadDaily(agentId, start, end);
        Map<LocalDate, DayAdditive> detail = new HashMap<>();
        for (Map<String, Object> row : traceMapper.dailyAgg(agentId, start, end)) {
            detail.put(toLocalDate(row.get("statDate")), new DayAdditive(num(row.get("calls")),
                    num(row.get("tokens")), num(row.get("errorCount")), dec(row.get("cost"))));
        }
        Map<LocalDate, Long> detailUsers = new HashMap<>();
        for (Map<String, Object> row : traceMapper.dailyDistinctUsers(agentId, start, end)) {
            detailUsers.put(toLocalDate(row.get("statDate")), num(row.get("users")));
        }

        long calls = 0;
        long tokens = 0;
        long errors = 0;
        BigDecimal cost = BigDecimal.ZERO;
        long dauSum = 0;
        long dauDays = 0;
        LocalDate today = LocalDate.now();
        for (LocalDate d = start.toLocalDate(); !d.isAfter(end.toLocalDate()); d = d.plusDays(1)) {
            LocalDateTime dayStart = d.atStartOfDay();
            LocalDateTime dayEnd = dayStart.plusDays(1);
            LocalDateTime s = start.isAfter(dayStart) ? start : dayStart;
            LocalDateTime e = end.isBefore(dayEnd) ? end : dayEnd;
            if (!s.isBefore(e)) continue;
            boolean fullDay = s.equals(dayStart) && e.equals(dayEnd) && d.isBefore(today);
            AgentMetricsDaily row = fullDay ? daily.get(d) : null;
            if (row != null) {
                calls += safe(row.getCalls());
                tokens += safe(row.getTokens());
                errors += safe(row.getErrorCount());
                cost = cost.add(row.getCost() == null ? BigDecimal.ZERO : row.getCost());
                dauSum += safe(row.getActiveUsers());
            } else {
                DayAdditive da = detail.getOrDefault(d, DayAdditive.ZERO);
                calls += da.calls();
                tokens += da.tokens();
                errors += da.errors();
                cost = cost.add(da.cost());
                dauSum += detailUsers.getOrDefault(d, 0L);
            }
            dauDays++;
        }

        double dau = dauDays == 0 ? 0 : round((double) dauSum / dauDays, 1);
        double sessionsPerUser = users == 0 ? 0 : round((double) sessions / users, 2);
        double successRate = calls == 0 ? 0 : round((1 - (double) errors / calls) * 100, 1);
        long p99 = ObservabilityService.percentile(traceMapper.latencies(agentId, start, end), 99);
        return new Agg(calls, errors, tokens, cost, sessions, users, dau, sessionsPerUser, successRate, p99);
    }

    private Map<LocalDate, AgentMetricsDaily> loadDaily(Long agentId, LocalDateTime start, LocalDateTime end) {
        List<AgentMetricsDaily> rows = dailyMapper.selectList(new LambdaQueryWrapper<AgentMetricsDaily>()
                .eq(agentId != null, AgentMetricsDaily::getAgentId, agentId)
                .ge(AgentMetricsDaily::getStatDate, start.toLocalDate())
                .le(AgentMetricsDaily::getStatDate, end.toLocalDate()));
        Map<LocalDate, AgentMetricsDaily> map = new HashMap<>();
        rows.forEach(r -> map.put(r.getStatDate(), r));
        return map;
    }

    /** 趋势：24 小时级范围按小时桶，更长范围按天桶（需求 3.3.3(2)(3)）。 */
    private Map<String, Object> trends(Long agentId, LocalDateTime start, LocalDateTime end) {
        boolean hourly = Duration.between(start, end).toDays() <= 2;
        String fmt = hourly ? "%Y-%m-%d %H:00" : "%Y-%m-%d";
        Map<String, long[]> byBucket = new HashMap<>();
        for (Map<String, Object> row : traceMapper.trendByBucket(agentId, start, end, fmt)) {
            byBucket.put(String.valueOf(row.get("bucket")), new long[]{num(row.get("calls")),
                    num(row.get("activeUsers")), num(row.get("errorCount"))});
        }
        List<String> buckets = ObservabilityService.timeBuckets(start, end, hourly);
        List<Long> calls = new ArrayList<>();
        List<Long> activeUsers = new ArrayList<>();
        List<Long> errors = new ArrayList<>();
        for (String bucket : buckets) {
            long[] v = byBucket.getOrDefault(bucket, new long[]{0, 0, 0});
            calls.add(v[0]);
            activeUsers.add(v[1]);
            errors.add(v[2]);
        }
        Map<String, Object> trends = new LinkedHashMap<>();
        trends.put("bucket", hourly ? "hour" : "day");
        trends.put("buckets", buckets);
        trends.put("activeUsers", activeUsers);
        trends.put("calls", calls);
        trends.put("errors", errors);
        return trends;
    }

    // ---------- 辅助 ----------

    private record Agg(long calls, long errorCount, long tokens, BigDecimal cost, long sessions, long users,
                       double dau, double sessionsPerUser, double successRate, long p99) {
    }

    private record DayAdditive(long calls, long tokens, long errors, BigDecimal cost) {
        static final DayAdditive ZERO = new DayAdditive(0, 0, 0, BigDecimal.ZERO);
    }

    private static double round(double v, int scale) {
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }

    private static long safe(Long v) {
        return v == null ? 0 : v;
    }

    private static long num(Object v) {
        return v == null ? 0 : ((Number) v).longValue();
    }

    private static BigDecimal dec(Object v) {
        if (v == null) return BigDecimal.ZERO;
        return v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v));
    }

    private static LocalDate toLocalDate(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate d) return d;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof java.util.Date d) return new java.sql.Date(d.getTime()).toLocalDate();
        return LocalDate.parse(String.valueOf(v).substring(0, 10));
    }
}
