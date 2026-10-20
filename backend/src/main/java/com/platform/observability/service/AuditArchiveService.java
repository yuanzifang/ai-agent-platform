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
 * 审计保留（默认 180 天）：旧月分区先复制到 audit_operation_archive，写入租户链锚点，
 * 再 DROP PARTITION。锚点让 verifyChain 从裁剪边界继续校验，而不是从 GENESIS 重来。
 */
@Service
@Slf4j
public class AuditArchiveService {

    private static final Pattern PARTITION_NAME = Pattern.compile("p\\d{6}");
    private static final String INSERT_ARCHIVE = """
            INSERT IGNORE INTO audit_operation_archive
              (id, tenant_id, user_id, op_type, object_type, object_id, op_result, detail, ip,
               prev_hash, hash_version, created_at, archived_at)
            SELECT id, tenant_id, user_id, op_type, object_type, object_id, op_result, detail, ip,
                   prev_hash, hash_version, created_at, NOW()
            FROM audit_operation PARTITION (%s)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${audit.archive.enabled:true}")
    private boolean enabled;

    @Value("${audit.archive.retention-days:180}")
    private int retentionDays;

    public AuditArchiveService(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Scheduled(cron = "${audit.archive.cron:0 10 4 * * *}")
    public void scheduled() {
        try {
            Map<String, Object> result = archiveExpired();
            if (!Boolean.TRUE.equals(result.get("skipped"))) {
                log.info("审计归档任务完成：{}", result);
            }
        } catch (Exception e) {
            log.warn("审计归档任务失败：{}", e.getMessage());
        }
    }

    public Map<String, Object> archiveExpired() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("retentionDays", Math.max(1, retentionDays));
        result.put("cutoff", LocalDate.now().minusDays(Math.max(1, retentionDays)).toString());
        if (!enabled) {
            result.put("skipped", true);
            result.put("reason", "audit.archive.enabled=false");
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
                        "WHERE table_schema = DATABASE() AND table_name = 'audit_operation' " +
                        "AND partition_name IS NOT NULL ORDER BY partition_ordinal_position");

        List<Partition> partitions = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String name = String.valueOf(row.get("partition_name"));
            if ("pmax".equalsIgnoreCase(name)) continue;
            try {
                LocalDate boundary = LocalDate.parse(String.valueOf(row.get("partition_description"))
                        .replace("'", "").trim());
                if (PARTITION_NAME.matcher(name).matches()) {
                    partitions.add(new Partition(name, boundary));
                }
            } catch (Exception e) {
                log.warn("忽略无法解析的审计分区 {}：{}", name, row.get("partition_description"));
            }
        }

        int archived = 0;
        int dropped = 0;
        List<String> droppedNames = new ArrayList<>();
        for (Partition p : partitions) {
            if (p.upper().isAfter(cutoff)) continue;
            try {
                Integer source = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM audit_operation PARTITION (" + p.name() + ")", Integer.class);
                int src = source == null ? 0 : source;
                jdbcTemplate.update(String.format(INSERT_ARCHIVE, p.name()));
                Integer archivedInSource = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM audit_operation PARTITION (" + p.name() + ") a " +
                                "JOIN audit_operation_archive ar ON ar.id = a.id AND ar.created_at = a.created_at",
                        Integer.class);
                int archivedCount = archivedInSource == null ? 0 : archivedInSource;
                if (archivedCount < src) {
                    throw new IllegalStateException("归档数量不足 partition=" + p.name()
                            + " source=" + src + " archive=" + archivedCount);
                }

                // 每个租户在该分区的最后一条记录，作为裁剪后的链锚点
                List<Map<String, Object>> lastRows = jdbcTemplate.queryForList(
                        "SELECT a.tenant_id, a.id, a.prev_hash FROM audit_operation PARTITION (" + p.name() + ") a " +
                                "JOIN (SELECT tenant_id, MAX(id) AS max_id FROM audit_operation PARTITION ("
                                + p.name() + ") GROUP BY tenant_id) x " +
                                "ON a.tenant_id = x.tenant_id AND a.id = x.max_id");
                for (Map<String, Object> last : lastRows) {
                    String hash = last.get("prev_hash") == null ? null : String.valueOf(last.get("prev_hash"));
                    if (hash == null || hash.isBlank()) {
                        throw new IllegalStateException("审计链尾缺少 prev_hash，拒绝删除 partition=" + p.name());
                    }
                    jdbcTemplate.update(
                            "INSERT INTO audit_chain_checkpoint " +
                                    "(tenant_id, partition_name, last_removed_id, last_removed_hash, created_at) " +
                                    "VALUES (?, ?, ?, ?, NOW()) ON DUPLICATE KEY UPDATE " +
                                    "last_removed_id = VALUES(last_removed_id), " +
                                    "last_removed_hash = VALUES(last_removed_hash), created_at = NOW()",
                            ((Number) last.get("tenant_id")).longValue(), p.name(),
                            ((Number) last.get("id")).longValue(), hash);
                }

                jdbcTemplate.execute("ALTER TABLE audit_operation DROP PARTITION " + p.name());
                archived += src;
                dropped++;
                droppedNames.add(p.name());
                log.info("审计分区已归档并删除：partition={} rows={} checkpoints={}", p.name(), src, lastRows.size());
            } catch (Exception e) {
                log.error("审计分区归档失败，停止后续删除：partition={}：{}", p.name(), e.getMessage());
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

    private record Partition(String name, LocalDate upper) {
    }
}
