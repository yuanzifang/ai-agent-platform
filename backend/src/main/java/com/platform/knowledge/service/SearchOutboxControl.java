package com.platform.knowledge.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 根据关键字检索配置打开/关闭 MySQL outbox 触发器，避免 ES 关闭时持续积累事件。 */
@Slf4j
@Component
public class SearchOutboxControl {

    private final JdbcTemplate mysqlJdbcTemplate;
    private final boolean enabled;

    public SearchOutboxControl(@Qualifier("jdbcTemplate") JdbcTemplate mysqlJdbcTemplate,
                               @Value("${search.es.enabled:false}") boolean enabled) {
        this.mysqlJdbcTemplate = mysqlJdbcTemplate;
        this.enabled = enabled;
    }

    @PostConstruct
    public void init() {
        try {
            mysqlJdbcTemplate.update("""
                    INSERT INTO knowledge_search_outbox_control (id, enabled)
                    VALUES (1, ?)
                    ON DUPLICATE KEY UPDATE enabled=?, updated_at=NOW()
                    """, enabled, enabled);
            log.info("搜索 outbox 开关 esEnabled={}", enabled);
        } catch (Exception e) {
            log.warn("搜索 outbox 开关初始化失败 esEnabled={} err={}", enabled, e.getMessage());
        }
    }
}
