package com.platform.system.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** ES 搜索 outbox 积压状态。 */
@Service
@RequiredArgsConstructor
public class SearchOutboxStatusService {

    private final @Qualifier("jdbcTemplate") JdbcTemplate mysqlJdbcTemplate;

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Long pending = mysqlJdbcTemplate.queryForObject(
                    "SELECT count(*) FROM knowledge_search_outbox WHERE status='PENDING'", Long.class);
            Long failed = mysqlJdbcTemplate.queryForObject(
                    "SELECT count(*) FROM knowledge_search_outbox WHERE status='FAILED'", Long.class);
            Long done = mysqlJdbcTemplate.queryForObject(
                    "SELECT count(*) FROM knowledge_search_outbox WHERE status='DONE'", Long.class);
            Object oldest = mysqlJdbcTemplate.queryForObject(
                    "SELECT MIN(available_at) FROM knowledge_search_outbox WHERE status='PENDING'",
                    Object.class);
            out.put("pending", pending == null ? 0 : pending);
            out.put("failed", failed == null ? 0 : failed);
            out.put("done", done == null ? 0 : done);
            out.put("oldestPendingAt", oldest == null ? null : String.valueOf(oldest));
            out.put("ok", true);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
        }
        return out;
    }
}
