package com.platform.observability.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 消息保留与归档（架构 8.4）：message 默认保留 90 天；整月分区先写入 message_archive，
 * 校验归档数量后再 DROP PARTITION，避免直接丢用户消息。
 *
 * <p>只处理“整个分区都早于保留截止日”的月分区；比如保留 90 天时，月中分区不会被提前删，
 * 实际保留窗口为 90~120 天。pmax 永不自动删除。</p>
 */
@Service
@Slf4j
public class MessageArchiveService {

    private static final Pattern PARTITION_NAME = Pattern.compile("p\\d{6}");
    private static final String INSERT_ARCHIVE = """
            INSERT IGNORE INTO message_archive
              (id, conversation_id, trace_id, role, parent_message_id, is_regenerated, content,
               attachments, status, audited_status, is_ai_generated, tokens, cost, tenant_id,
               created_at, archived_at)
            SELECT id, conversation_id, trace_id, role, parent_message_id, is_regenerated, content,
                   attachments, status, audited_status, is_ai_generated, tokens, cost, tenant_id,
                   created_at, NOW()
            FROM message PARTITION (%s)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${message.archive.enabled:true}")
    private boolean enabled;

    @Value("${message.archive.retention-days:90}")
    private int retentionDays;

    public MessageArchiveService(DataSource dataSource) {
        // 显式使用 MySQL 主 DataSource；容器里另有 pgJdbcTemplate
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Scheduled(cron = "${message.archive.cron:0 40 3 * * *}")
    public void scheduled() {
        try {
            Map<String, Object> result = archiveExpired();
            if (!Boolean.TRUE.equals(result.get("skipped"))) {
                log.info("消息归档任务完成：{}", result);
            }
        } catch (Exception e) {
            log.warn("消息归档任务失败：{}", e.getMessage());
        }
    }

    /**
     * 手动触发（平台管理员接口）和定时任务共用；同一时刻只允许一个归档任务。
     */
    public Map<String, Object> archiveExpired() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("retentionDays", Math.max(1, retentionDays));
        result.put("cutoff", LocalDate.now().minusDays(Math.max(1, retentionDays)).toString());
        if (!enabled) {
            result.put("skipped", true);
            result.put("reason", "message.archive.enabled=false");
            return result;
        }
        if (!running.compareAndSet(false, true)) {
            result.put("skipped", true);
            result.put("reason", "already-running");
            return result;
        }
        try {
            return doArchive(result);
        } finally {
            running.set(false);
        }
    }

    private Map<String, Object> doArchive(Map<String, Object> result) {
        LocalDate cutoff = LocalDate.now().minusDays(Math.max(1, retentionDays));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT partition_name, partition_description FROM information_schema.partitions " +
                        "WHERE table_schema = DATABASE() AND table_name = 'message' " +
                        "AND partition_name IS NOT NULL ORDER BY partition_ordinal_position");

        List<Partition> partitions = new ArrayList<>();
        LocalDate previous = null;
        for (Map<String, Object> row : rows) {
            String name = String.valueOf(row.get("partition_name"));
            if ("pmax".equalsIgnoreCase(name)) continue;
            LocalDate boundary;
            try {
                boundary = LocalDate.parse(String.valueOf(row.get("partition_description"))
                        .replace("'", "").trim());
            } catch (Exception e) {
                log.warn("忽略无法解析的消息分区 {}：{}", name, row.get("partition_description"));
                continue;
            }
            if (PARTITION_NAME.matcher(name).matches()) {
                partitions.add(new Partition(name, previous, boundary));
            }
            previous = boundary;
        }

        int archived = 0;
        int dropped = 0;
        List<String> droppedNames = new ArrayList<>();
        for (Partition p : partitions) {
            // 整个分区必须都早于或等于保留截止日，不能只按月中日期删整月
            if (p.upper().isAfter(cutoff)) continue;
            try {
                Integer source = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM message PARTITION (" + p.name() + ")", Integer.class);
                int src = source == null ? 0 : source;
                jdbcTemplate.update(String.format(INSERT_ARCHIVE, p.name()));
                // 按主键 join 校验“该分区每一行都已归档”，避免仅按时间范围统计被其他行抵消
                Integer archivedInSource = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM message PARTITION (" + p.name() + ") m " +
                                "JOIN message_archive a ON a.id = m.id AND a.created_at = m.created_at",
                        Integer.class);
                int archivedCount = archivedInSource == null ? 0 : archivedInSource;
                if (archivedCount < src) {
                    throw new IllegalStateException("归档数量不足 partition=" + p.name()
                            + " source=" + src + " archive=" + archivedCount);
                }
                jdbcTemplate.execute("ALTER TABLE message DROP PARTITION " + p.name());
                archived += src;
                dropped++;
                droppedNames.add(p.name());
                log.info("消息分区已归档并删除：partition={} rows={}", p.name(), src);
            } catch (Exception e) {
                log.error("消息分区归档失败，停止后续删除：partition={}：{}", p.name(), e.getMessage());
                throw e;
            }
        }
        result.put("archivedRows", archived);
        result.put("droppedPartitions", dropped);
        result.put("partitions", droppedNames);
        result.put("skipped", false);
        result.put("archivedAt", LocalDateTime.now().toString());
        return result;
    }

    private record Partition(String name, LocalDate lower, LocalDate upper) {
    }
}
