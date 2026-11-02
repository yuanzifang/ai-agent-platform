package com.platform.observability.service;

import com.platform.observability.mapper.AgentMetricsDailyMapper;
import com.platform.observability.mapper.AgentTraceMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P0：监控统计环比使用等长上一周期，无数据回落为 0。 */
class MetricsServiceTest {

    @Test
    void customPeriodUsesEqualLengthPreviousPeriodAndComputesChangeRatio() {
        AgentTraceMapper traceMapper = mock(AgentTraceMapper.class);
        AgentMetricsDailyMapper dailyMapper = mock(AgentMetricsDailyMapper.class);
        MetricsService service = new MetricsService(traceMapper, dailyMapper);
        LocalDate currentDay = LocalDate.of(2026, 1, 1);
        LocalDate previousDay = currentDay.minusDays(1);
        LocalDateTime currentStart = currentDay.atStartOfDay();
        LocalDateTime currentEnd = currentStart.plusDays(1);

        when(dailyMapper.selectList(any())).thenReturn(List.of());
        when(traceMapper.distinctAgg(eq(7L), any(), any())).thenAnswer(inv ->
                inv.getArgument(1, LocalDateTime.class).equals(currentStart)
                        ? Map.of("sessions", 2L, "users", 2L)
                        : Map.of("sessions", 1L, "users", 1L));
        when(traceMapper.dailyAgg(eq(7L), any(), any())).thenAnswer(inv ->
                inv.getArgument(1, LocalDateTime.class).equals(currentStart)
                        ? List.of(day(currentDay, 10, 100, 1, new BigDecimal("1.0")))
                        : List.of(day(previousDay, 5, 50, 0, new BigDecimal("0.5"))));
        when(traceMapper.dailyDistinctUsers(eq(7L), any(), any())).thenAnswer(inv ->
                inv.getArgument(1, LocalDateTime.class).equals(currentStart)
                        ? List.of(Map.of("statDate", currentDay, "users", 2L))
                        : List.of(Map.of("statDate", previousDay, "users", 1L)));
        when(traceMapper.latencies(eq(7L), any(), any())).thenAnswer(inv ->
                inv.getArgument(1, LocalDateTime.class).equals(currentStart)
                        ? List.of(10L, 20L, 30L) : List.of(5L));
        when(traceMapper.trendByBucket(eq(7L), any(), any(), any())).thenReturn(List.of());

        Map<String, Object> result = service.agentMetrics(7L, "custom",
                "2026-01-01T00:00:00", "2026-01-02T00:00:00");

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> metrics = (Map<String, Map<String, Object>>) result.get("metrics");
        assertEquals(10L, ((Number) metrics.get("calls").get("value")).longValue());
        assertEquals(5L, ((Number) metrics.get("calls").get("prev")).longValue());
        assertEquals(100.0, ((Number) metrics.get("calls").get("changeRatio")).doubleValue());
        assertEquals(90.0, ((Number) metrics.get("successRate").get("value")).doubleValue());
        assertEquals(-10.0, ((Number) metrics.get("successRate").get("changeRatio")).doubleValue());
        assertEquals(0.0, ((Number) metrics.get("errorCount").get("changeRatio")).doubleValue(),
                "上一周期为 0 时环比应为 0");
        assertEquals(30L, ((Number) metrics.get("p99LatencyMs").get("value")).longValue());

        ArgumentCaptor<LocalDateTime> starts = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> ends = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(traceMapper, times(2)).distinctAgg(eq(7L), starts.capture(), ends.capture());
        assertEquals(currentStart, starts.getAllValues().get(0));
        assertEquals(currentEnd, ends.getAllValues().get(0));
        assertEquals(currentStart.minusDays(1), starts.getAllValues().get(1));
        assertEquals(currentStart, ends.getAllValues().get(1));
    }

    @Test
    void emptyDataRendersZeroValuesAndZeroTrendBuckets() {
        AgentTraceMapper traceMapper = mock(AgentTraceMapper.class);
        AgentMetricsDailyMapper dailyMapper = mock(AgentMetricsDailyMapper.class);
        MetricsService service = new MetricsService(traceMapper, dailyMapper);
        when(dailyMapper.selectList(any())).thenReturn(List.of());
        when(traceMapper.distinctAgg(any(), any(), any())).thenReturn(Map.of());
        when(traceMapper.dailyAgg(any(), any(), any())).thenReturn(List.of());
        when(traceMapper.dailyDistinctUsers(any(), any(), any())).thenReturn(List.of());
        when(traceMapper.latencies(any(), any(), any())).thenReturn(List.of());
        when(traceMapper.trendByBucket(any(), any(), any(), any())).thenReturn(List.of());

        Map<String, Object> result = service.agentMetrics(7L, "24h", null, null);

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> metrics = (Map<String, Map<String, Object>>) result.get("metrics");
        metrics.forEach((name, metric) -> {
            assertEquals(0.0, ((Number) metric.get("value")).doubleValue(), name);
            assertEquals(0.0, ((Number) metric.get("changeRatio")).doubleValue(), name);
        });
        @SuppressWarnings("unchecked")
        Map<String, Object> trends = (Map<String, Object>) result.get("trends");
        assertTrue(((List<?>) trends.get("buckets")).size() > 0);
        assertTrue(((List<?>) trends.get("calls")).stream().allMatch(v -> ((Number) v).longValue() == 0));
        assertTrue(((List<?>) trends.get("errors")).stream().allMatch(v -> ((Number) v).longValue() == 0));
    }

    private Map<String, Object> day(LocalDate date, long calls, long tokens, long errors, BigDecimal cost) {
        return Map.of("statDate", date, "calls", calls, "tokens", tokens,
                "errorCount", errors, "cost", cost);
    }
}
