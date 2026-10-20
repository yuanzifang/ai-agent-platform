package com.platform.observability.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * 大表月分区维护（架构 8.4）。
 *
 * <p>V48 已创建 2026-01 .. 2028-12 的月分区；本服务在启动和每日低峰自动把 pmax
 * 拆成未来月份，避免新数据全部落入 MAXVALUE 分区导致无法按时间归档/裁剪。</p>
 *
 * <p>只做“补未来分区”，不自动删除历史分区。审计/消息的保留期与归档涉及合规和用户数据，
 * 必须由独立的保留策略/人工窗口执行；可配置项见 application.yml。</p>
 */
@Service
@Slf4j
public class PartitionMaintenanceService {

    private static final List<String> PARTITIONED_TABLES = List.of(
            "agent_trace", "agent_span", "audit_operation", "conversation", "message", "system_log");
    private static final DateTimeFormatter MONTH_NAME = DateTimeFormatter.ofPattern("yyyyMM");

    private final JdbcTemplate jdbcTemplate;

    public PartitionMaintenanceService(DataSource dataSource) {
        // PgVectorConfig 另行注册 pgJdbcTemplate；这里显式使用 MySQL 主 DataSource
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Value("${storage.partition.enabled:true}")
    private boolean enabled;

    @Value("${storage.partition.months-ahead:3}")
    private int monthsAhead;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        maintain();
    }

    @Scheduled(cron = "${storage.partition.cron:0 20 2 * * *}")
    public void scheduled() {
        maintain();
    }

    /** 幂等补齐所有白名单表的未来月分区。 */
    public void maintain() {
        if (!enabled) return;
        int ahead = Math.max(0, Math.min(monthsAhead, 36));
        LocalDate target = YearMonth.now().plusMonths(ahead + 1L).atDay(1);
        int created = 0;
        for (String table : PARTITIONED_TABLES) {
            try {
                created += ensure(table, target);
            } catch (Exception e) {
                // 分区维护不能阻止应用启动；权限/DDL 冲突在日志中暴露给运维
                log.warn("分区维护失败 table={}：{}", table, e.getMessage());
            }
        }
        if (created > 0) log.info("分区维护完成：新增 {} 个月分区（目标边界 {}）", created, target);
    }

    private int ensure(String table, LocalDate target) {
        // table 来自白名单，不接受外部输入
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT partition_name, partition_description FROM information_schema.partitions " +
                        "WHERE table_schema = DATABASE() AND table_name = '" + table + "' " +
                        "AND partition_name IS NOT NULL ORDER BY partition_ordinal_position");
        LocalDate lastBoundary = null;
        boolean hasMax = false;
        for (Map<String, Object> row : rows) {
            String name = String.valueOf(row.get("partition_name"));
            if ("pmax".equalsIgnoreCase(name)) {
                hasMax = true;
                continue;
            }
            try {
                LocalDate d = LocalDate.parse(String.valueOf(row.get("partition_description"))
                        .replace("'", "").trim());
                if (lastBoundary == null || d.isAfter(lastBoundary)) lastBoundary = d;
            } catch (Exception ignored) {
                // 非日期描述（例如旧的定义）由后续 DDL 报错并记录
            }
        }
        if (!hasMax) {
            log.warn("表 {} 没有 pmax 分区，跳过自动补齐", table);
            return 0;
        }

        LocalDate cursor = lastBoundary == null
                ? YearMonth.now().plusMonths(1).atDay(1)
                : lastBoundary.plusMonths(1);
        if (cursor.isAfter(target)) return 0;

        List<String> defs = new ArrayList<>();
        while (!cursor.isAfter(target)) {
            YearMonth month = YearMonth.from(cursor.minusMonths(1));
            defs.add("PARTITION p" + month.format(MONTH_NAME)
                    + " VALUES LESS THAN ('" + cursor + "')");
            cursor = cursor.plusMonths(1);
        }
        if (defs.isEmpty()) return 0;

        StringBuilder ddl = new StringBuilder("ALTER TABLE `").append(table)
                .append("` REORGANIZE PARTITION pmax INTO (");
        for (int i = 0; i < defs.size(); i++) {
            if (i > 0) ddl.append(", ");
            ddl.append(defs.get(i));
        }
        ddl.append(", PARTITION pmax VALUES LESS THAN (MAXVALUE))");
        jdbcTemplate.execute(ddl.toString());
        log.info("新增月分区 table={} count={} until={}", table, defs.size(), target);
        return defs.size();
    }
}
