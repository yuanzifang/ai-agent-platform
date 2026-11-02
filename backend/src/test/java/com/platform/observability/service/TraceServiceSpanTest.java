package com.platform.observability.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.observability.entity.AgentSpan;
import com.platform.observability.mapper.AgentSpanMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** P0：Trace 详情 Span 结构保留 agent/chain/llm/tool 四类及父子关系。 */
class TraceServiceSpanTest {

    @Test
    void spansOfPreservesFourTypesAndParentLinks() {
        AgentSpanMapper spanMapper = mock(AgentSpanMapper.class);
        TraceService service = new TraceService(null, spanMapper, null, null, null,
                new ObjectMapper(), null);
        when(spanMapper.selectList(any())).thenReturn(List.of(
                span("root", null, "agent"),
                span("chain-1", "root", "chain"),
                span("llm-1", "root", "llm"),
                span("tool-1", "chain-1", "tool")
        ));

        List<Map<String, Object>> spans = service.spansOf("trace-p0");

        assertEquals(List.of("agent", "chain", "llm", "tool"),
                spans.stream().map(s -> String.valueOf(s.get("spanType"))).toList());
        assertEquals("root", spans.get(0).get("spanId"));
        assertNull(spans.get(0).get("parentSpanId"));
        assertEquals("root", spans.get(1).get("parentSpanId"));
        assertEquals("chain-1", spans.get(3).get("parentSpanId"));
    }

    private AgentSpan span(String spanId, String parent, String type) {
        AgentSpan s = new AgentSpan();
        s.setSpanId(spanId);
        s.setParentSpanId(parent);
        s.setSpanType(type);
        s.setName(type + "-name");
        s.setStatus("SUCCESS");
        s.setStartTime(LocalDateTime.now());
        s.setEndTime(LocalDateTime.now());
        s.setDurationMs(10L);
        s.setCreatedAt(LocalDateTime.now());
        return s;
    }
}
