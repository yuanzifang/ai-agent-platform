package com.platform.observability.service;

import com.platform.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 灰度 A/B 对比：分组条件（JSON_CONTAINS 互斥）、评测分组、delta 计算。 */
class GrayCompareServiceTest {

    private JdbcTemplate jdbc;

    private GrayCompareService serviceWithStubbedRows() {
        jdbc = mock(JdbcTemplate.class);
        // 依次返回：基线组、灰度组（成本有差以验证 delta）、工具调用统计
        Map<String, Object> baselineRow = Map.of(
                "requests", 10L, "errors", 2L, "avg_latency", 120.0,
                "avg_input_tokens", 50.0, "avg_output_tokens", 80.0, "avg_total_tokens", 130.0,
                "total_cost", 1.0, "avg_cost", 0.1);
        Map<String, Object> grayRow = Map.of(
                "requests", 10L, "errors", 1L, "avg_latency", 100.0,
                "avg_input_tokens", 50.0, "avg_output_tokens", 80.0, "avg_total_tokens", 130.0,
                "total_cost", 2.0, "avg_cost", 0.2);
        Map<String, Object> toolRow = Map.of("tools", 30L, "traces", 10L);
        when(jdbc.queryForMap(anyString(), any(Object[].class)))
                .thenReturn(baselineRow, grayRow, toolRow);
        when(jdbc.queryForObject(anyString(), eq(Number.class), any(Object[].class))).thenReturn(900L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        // eval 分组：基线 4 条均分 0.5，灰度 2 条均分 0.8
        return new GrayCompareService(jdbc);
    }

    private void stubEvalScores(double baselineAvg, double grayAvg) {
        when(jdbc.queryForMap(contains("eval_result"), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            return sql.contains("gray_tag = ?")
                    ? Map.of("evals", 2L, "avg_score", grayAvg, "passed", 2L, "unscored", 0L)
                    : Map.of("evals", 4L, "avg_score", baselineAvg, "passed", 2L, "unscored", 0L);
        });
    }

    @Test
    void groupConditionsAreMutuallyExclusive() {
        GrayCompareService service = serviceWithStubbedRows();
        stubEvalScores(0.5, 0.8);
        com.platform.common.context.TenantContext.runWith(1L, () -> service.compare("canary-v2", 7, null));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .queryForMap(sql.capture(), any(Object[].class));
        String all = String.join("\n", sql.getAllValues());
        assertTrue(all.contains("JSON_CONTAINS(tags, JSON_QUOTE('gray:canary-v2'))"), "灰度组条件");
        assertTrue(all.contains("NOT JSON_CONTAINS(tags, JSON_QUOTE('gray:canary-v2'))"), "基线组条件（互斥）");
    }

    @Test
    void deltaIsGrayMinusBaseline() {
        GrayCompareService service = serviceWithStubbedRows();
        stubEvalScores(0.5, 0.8);
        Map<String, Object> result = com.platform.common.context.TenantContext.runWith(1L, () -> service.compare("canary-v2", 7, null));
        @SuppressWarnings("unchecked")
        Map<String, Object> delta = (Map<String, Object>) result.get("delta");
        assertEquals(1.0, ((Number) delta.get("totalCost")).doubleValue(), 0.0001, "灰度组成本 - 基线组成本");
        @SuppressWarnings("unchecked")
        Map<String, Object> eval = (Map<String, Object>) result.get("eval");
        assertEquals(0.8, ((Number) ((Map<?, ?>) eval.get("gray")).get("avgScore")).doubleValue(), 0.0001);
        assertEquals(0.5, ((Number) ((Map<?, ?>) eval.get("baseline")).get("avgScore")).doubleValue(), 0.0001);
    }

    @Test
    void blankTagRejected() {
        GrayCompareService service = serviceWithStubbedRows();
        assertThrows(BusinessException.class, () -> service.compare("  ", 7, null));
        assertThrows(BusinessException.class, () -> service.compare(null, 7, null));
    }

    @Test
    void sanitizeStripsUnsafeChars() {
        assertEquals("canary-v2", GrayCompareService.sanitize(" canary-v2 "));
        assertNull(GrayCompareService.sanitize("标记"));
    }
}
