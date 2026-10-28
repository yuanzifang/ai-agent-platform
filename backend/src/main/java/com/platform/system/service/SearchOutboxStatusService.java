package com.platform.system.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** ES 搜索 outbox 积压状态与 Prometheus 指标。 */
@Service
@RequiredArgsConstructor
public class SearchOutboxStatusService {

    private final @Qualifier("jdbcTemplate") JdbcTemplate mysqlJdbcTemplate;
    private final MeterRegistry meterRegistry;

    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong oldestPendingSeconds = new AtomicLong();
    private final AtomicInteger available = new AtomicInteger();

    @PostConstruct
    public void registerMetrics() {
        Gauge.builder("knowledge_search_outbox_pending", pending, AtomicLong::get)
                .description("待处理的 ES 搜索 outbox 数量")
                .register(meterRegistry);
        Gauge.builder("knowledge_search_outbox_failed", failed, AtomicLong::get)
                .description("已终止失败的 ES 搜索 outbox 数量")
                .register(meterRegistry);
        Gauge.builder("knowledge_search_outbox_oldest_pending_seconds",
                        oldestPendingSeconds, AtomicLong::get)
                .description("最老待处理 ES 搜索 outbox 的等待秒数")
                .register(meterRegistry);
        Gauge.builder("knowledge_search_outbox_available", available, AtomicInteger::get)
                .description("outbox 状态查询是否可用（1=是，0=否）")
                .register(meterRegistry);
        refresh();
    }

    @Scheduled(fixedDelayString = "${search.outbox.metrics-interval-ms:30000}")
    public void refresh() {
        Map<String, Object> current = status();
        if (Boolean.TRUE.equals(current.get("ok"))) {
            pending.set(asLong(current.get("pending")));
            failed.set(asLong(current.get("failed")));
            oldestPendingSeconds.set(asLong(current.get("oldestPendingAgeSeconds")));
            available.set(1);
        } else {
            available.set(0);
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Long pendingCount = mysqlJdbcTemplate.queryForObject(
                    "SELECT count(*) FROM knowledge_search_outbox WHERE status='PENDING'", Long.class);
            Long failedCount = mysqlJdbcTemplate.queryForObject(
                    "SELECT count(*) FROM knowledge_search_outbox WHERE status='FAILED'", Long.class);
            Long done = mysqlJdbcTemplate.queryForObject(
                    "SELECT count(*) FROM knowledge_search_outbox WHERE status='DONE'", Long.class);
            Object oldest = mysqlJdbcTemplate.queryForObject(
                    "SELECT MIN(available_at) FROM knowledge_search_outbox WHERE status='PENDING'",
                    Object.class);
            out.put("pending", pendingCount == null ? 0 : pendingCount);
            out.put("failed", failedCount == null ? 0 : failedCount);
            out.put("done", done == null ? 0 : done);
            out.put("oldestPendingAt", oldest == null ? null : String.valueOf(oldest));
            out.put("oldestPendingAgeSeconds", oldest == null ? 0L : ageSeconds(oldest));
            out.put("ok", true);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    private long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private long ageSeconds(Object value) {
        if (value == null) return 0L;
        try {
            Instant instant;
            if (value instanceof Timestamp ts) {
                instant = ts.toInstant();
            } else if (value instanceof LocalDateTime ldt) {
                instant = ldt.atZone(ZoneId.systemDefault()).toInstant();
            } else if (value instanceof java.util.Date date) {
                instant = date.toInstant();
            } else {
                instant = Instant.parse(String.valueOf(value));
            }
            return Math.max(0L, Duration.between(instant, Instant.now()).getSeconds());
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
