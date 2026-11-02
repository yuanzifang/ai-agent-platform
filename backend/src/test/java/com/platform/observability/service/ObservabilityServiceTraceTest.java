package com.platform.observability.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.observability.entity.AgentTrace;
import com.platform.observability.mapper.AgentSpanMapper;
import com.platform.observability.mapper.AgentTraceMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P0：Trace 列表分页、筛选参数映射和关键词查询。 */
class ObservabilityServiceTraceTest {

    @Test
    void tracesClampsPaginationAndMapsFilters() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentTrace.class);
        AgentTraceMapper traceMapper = mock(AgentTraceMapper.class);
        AgentSpanMapper spanMapper = mock(AgentSpanMapper.class);
        TraceService traceService = mock(TraceService.class);
        ObservabilityService service = new ObservabilityService(traceMapper, spanMapper,
                traceService, new ObjectMapper());

        AgentTrace trace = new AgentTrace();
        trace.setTraceId("trace-p0");
        trace.setKind("internal");
        trace.setAgentId(7L);
        trace.setAgentName("drill-agent");
        trace.setStatus("SUCCESS");
        trace.setInputText("keyword-hit");
        trace.setOutputText("ok");
        trace.setLatencyMs(12L);
        trace.setTotalTokens(34L);
        trace.setCreatedAt(LocalDateTime.now());
        Page<AgentTrace> page = new Page<>(2, 1);
        page.setRecords(List.of(trace));
        page.setTotal(2);
        when(traceMapper.selectPage(any(), any())).thenReturn(page);

        Map<String, Object> result = service.traces(new ObservabilityService.AgentTraceQuery(
                7L, "success", "internal", "V1", List.of("prod"), List.of(1L), List.of("session-1"),
                10L, 100L, 1L, 100L, LocalDateTime.now().minusDays(1), LocalDateTime.now(),
                "keyword-hit", true, 2L, 1000L));

        assertEquals(2L, result.get("page"));
        assertEquals(100L, result.get("pageSize"));
        assertEquals(2L, result.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("list");
        assertEquals(1, rows.size());
        assertEquals("trace-p0", rows.get(0).get("traceId"));

        ArgumentCaptor<Page<AgentTrace>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<AgentTrace>> wrapperCaptor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(traceMapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());
        assertEquals(2L, pageCaptor.getValue().getCurrent());
        assertEquals(100L, pageCaptor.getValue().getSize());
        String sql = wrapperCaptor.getValue().getSqlSegment();
        assertTrue(sql.contains("agent_id"), sql);
        assertTrue(sql.contains("status"), sql);
        assertTrue(sql.contains("kind"), sql);
        assertTrue(sql.contains("latency_ms"), sql);
        assertTrue(sql.contains("trace_id"), sql);
        assertTrue(sql.contains("input_text"), sql);
        assertTrue(sql.contains("tenant_id") || sql.contains("JSON_CONTAINS"), sql);
    }
}
