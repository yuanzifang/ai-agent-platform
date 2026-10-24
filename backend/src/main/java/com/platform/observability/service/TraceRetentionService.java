package com.platform.observability.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Trace/Span 保留清理（架构 8.4）：默认保留 30 天，按 created_at 分批删除，
 * 每批默认 1000 行，避免大表长事务/锁表；先删 Span 再删 Trace。
 */
@Slf4j
@Service
public class TraceRetentionService {

    private static final int MAX_BATCHES = 1000;

    private final JdbcTemplate jdbcTemplate;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${observability.trace.retention-enabled:true}")
    private boolean enabled;

    @Value("${observability.trace.retention-days:30}")
    private int retentionDays;

    @Value("${observability.trace.cleanup-batch:1000}")
    private int batchSize;

    public TraceRetentionService(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Scheduled(cron = "${observability.trace.cleanup-cron:0 50 4 * * *}")
    public void scheduled() {
        try {
            Map<String, Object> result = cleanupExpired();
            if (!Boolean.TRUE.equals(result.get("skipped"))) {
                log.info("Trace 保留清理完成：{}", result);
            }
        } catch (Exception e) {
            log.warn("Trace 保留清理失败：{}", e.getMessage());
        }
    }

    /** 手动触发与定时任务共用；同一时刻只允许一个清理任务。 */
    public Map<String, Object> cleanupExpired() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("retentionDays", Math.max(1, retentionDays));
        if (!enabled) {
            result.put("skipped", true);
            result.put("reason", "observability.trace.retention-enabled=false");
            return result;
        }
        if (!running.compareAndSet(false, true)) {
            result.put("skipped", true);
            result.put("reason", "already-running");
            return result;
        }
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(1, retentionDays));
            int batch = Math.max(100, Math.min(batchSize, 10000));
            int spans = deleteBatches("DELETE FROM agent_span WHERE created_at < ? LIMIT ?", cutoff, batch);
            int traces = deleteBatches("DELETE FROM agent_trace WHERE created_at < ? LIMIT ?", cutoff, batch);
            result.put("cutoff", cutoff.toString());
            result.put("spansDeleted", spans);
            result.put("tracesDeleted", traces);
            result.put("batchSize", batch);
            return result;
        } finally {
            running.set(false);
        }
    }

    private int deleteBatches(String sql, LocalDateTime cutoff, int batch) {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int deleted = jdbcTemplate.update(sql, cutoff, batch);
            total += deleted;
            if (deleted < batch) break;
        }
        return total;
    }
}
