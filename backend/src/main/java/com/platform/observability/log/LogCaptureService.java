package com.platform.observability.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import com.platform.common.context.TenantContext;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 应用日志采集：Logback 异步入队 → 定时批量写 system_log，供可观测 Logs 页签查询。
 * 仅采集 com.platform.* 的 INFO+ 日志与全局 WARN+，避免框架 INFO 噪音；保留期可配置。
 * 生产如需标准 OTel Logs，可将同一事件额外导出到 OTLP/Loki。
 */
@Slf4j
@Service
public class LogCaptureService {

    private static final String INSERT_SQL = """
            INSERT INTO system_log (tenant_id, level, logger, message, exception, trace_id, thread, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String CAPTURE_LOGGER = LogCaptureService.class.getName();

    private final JdbcTemplate jdbcTemplate;

    public LogCaptureService(javax.sql.DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Value("${system.log.capture-level:INFO}")
    private String captureLevel;

    @Value("${system.log.queue-size:5000}")
    private int queueSize;

    @Value("${system.log.max-message-length:4000}")
    private int maxMessageLength;

    @Value("${system.log.retention-days:7}")
    private int retentionDays;

    private BlockingQueue<Entry> queue;
    private Level minLevel;
    private final AtomicLong dropped = new AtomicLong();

    private record Entry(Long tenantId, String level, String logger, String message,
                         String exception, String traceId, String thread, LocalDateTime createdAt) {
    }

    @PostConstruct
    void init() {
        this.queue = new ArrayBlockingQueue<>(Math.max(200, queueSize));
        this.minLevel = Level.toLevel(captureLevel, Level.INFO);
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        CaptureAppender appender = new CaptureAppender();
        appender.setContext(root.getLoggerContext());
        appender.setName("system-log-capture");
        appender.start();
        root.addAppender(appender);
        log.info("运行日志采集已启用：level={} queue={} retention={}d", minLevel, queue.size(), retentionDays);
    }

    private final class CaptureAppender extends AppenderBase<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent event) {
            try {
                if (event == null || queue == null) return;
                String loggerName = event.getLoggerName() == null ? "" : event.getLoggerName();
                if (loggerName.startsWith(CAPTURE_LOGGER)) return;
                Level level = event.getLevel();
                if (!level.isGreaterOrEqual(minLevel)) return;
                // 平台 INFO 全量；其他框架只收 WARN/ERROR，避免启动噪音
                if (!level.isGreaterOrEqual(Level.WARN) && !loggerName.startsWith("com.platform")) return;

                String message = truncate(event.getFormattedMessage(), maxMessageLength);
                String exception = event.getThrowableProxy() == null ? null
                        : truncate(ThrowableProxyUtil.asString(event.getThrowableProxy()), 8000);
                String traceId = event.getMDCPropertyMap() == null ? MDC.get("traceId")
                        : event.getMDCPropertyMap().getOrDefault("traceId", MDC.get("traceId"));
                Long tenantId = TenantContext.getTenantId();
                Entry entry = new Entry(tenantId, level.toString(), truncate(loggerName, 160),
                        message, exception, traceId, truncate(event.getThreadName(), 80), LocalDateTime.now());
                if (!queue.offer(entry)) dropped.incrementAndGet();
            } catch (Exception e) {
                // 日志采集绝不能影响业务
                log.warn("日志采集失败（不影响业务）：{}", e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelayString = "${system.log.flush-ms:2000}")
    public void flush() {
        if (queue == null || queue.isEmpty()) return;
        List<Entry> batch = new ArrayList<>(200);
        queue.drainTo(batch, 200);
        if (batch.isEmpty()) return;
        try {
            List<Object[]> args = new ArrayList<>(batch.size());
            for (Entry e : batch) {
                args.add(new Object[]{e.tenantId(), e.level(), e.logger(), e.message(),
                        e.exception(), e.traceId(), e.thread(), e.createdAt()});
            }
            jdbcTemplate.batchUpdate(INSERT_SQL, args);
        } catch (Exception e) {
            // 数据库暂不可用时丢弃本批，避免队列无限增长；可观测页会体现日志缺口
            log.debug("运行日志落库失败，丢弃 {} 条：{}", batch.size(), e.getMessage());
        }
    }

    @Scheduled(cron = "${system.log.cleanup-cron:0 30 3 * * *}")
    public void cleanup() {
        try {
            int deleted = jdbcTemplate.update("DELETE FROM system_log WHERE created_at < ?",
                    LocalDateTime.now().minusDays(Math.max(1, retentionDays)));
            if (deleted > 0) log.info("运行日志清理完成：{} 条（保留 {} 天）", deleted, retentionDays);
        } catch (Exception e) {
            log.debug("运行日志清理失败：{}", e.getMessage());
        }
    }

    public long droppedCount() {
        return dropped.get();
    }

    private String truncate(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
