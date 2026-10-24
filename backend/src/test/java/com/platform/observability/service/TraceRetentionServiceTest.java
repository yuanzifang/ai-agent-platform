package com.platform.observability.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceRetentionServiceTest {

    @Test
    void deletesSpanAndTraceInBatches() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(LocalDateTime.class), anyInt()))
                .thenReturn(1000, 12, 1000, 3);
        TraceRetentionService service = new TraceRetentionService(mock(DataSource.class));
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "retentionDays", 30);
        ReflectionTestUtils.setField(service, "batchSize", 1000);

        Map<String, Object> result = service.cleanupExpired();
        assertEquals(1012, result.get("spansDeleted"));
        assertEquals(1003, result.get("tracesDeleted"));
        assertEquals(30, result.get("retentionDays"));
    }

    @Test
    void canBeDisabled() {
        TraceRetentionService service = new TraceRetentionService(mock(DataSource.class));
        ReflectionTestUtils.setField(service, "enabled", false);
        Map<String, Object> result = service.cleanupExpired();
        assertTrue(Boolean.TRUE.equals(result.get("skipped")));
    }
}
