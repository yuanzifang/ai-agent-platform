package com.platform.knowledge.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * outbox 批次认领。SELECT ... FOR UPDATE SKIP LOCKED 保证多副本不会拿到同一批行；
 * locked_until 允许 worker 崩溃后由其他副本接管。
 */
@Service
@RequiredArgsConstructor
public class SearchOutboxClaimService {

    private final @Qualifier("jdbcTemplate") JdbcTemplate mysqlJdbcTemplate;
    private final PlatformTransactionManager txManager;

    public List<Map<String, Object>> claim(String workerId, int limit, int lockSeconds) {
        int size = Math.max(1, Math.min(limit, 1000));
        int lock = Math.max(10, Math.min(lockSeconds, 3600));
        TransactionTemplate tx = new TransactionTemplate(txManager);
        return tx.execute(status -> {
            List<Map<String, Object>> rows = mysqlJdbcTemplate.queryForList("""
                    SELECT id, tenant_id, chunk_id, operation, attempts
                    FROM knowledge_search_outbox
                    WHERE status = 'PENDING' AND available_at <= NOW()
                      AND (locked_until IS NULL OR locked_until < NOW())
                    ORDER BY id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                    """, size);
            for (Map<String, Object> row : rows) {
                long id = ((Number) row.get("id")).longValue();
                mysqlJdbcTemplate.update("""
                        UPDATE knowledge_search_outbox
                        SET locked_by=?, locked_until=DATE_ADD(NOW(), INTERVAL %d SECOND), updated_at=NOW()
                        WHERE id=? AND status='PENDING'
                          AND (locked_until IS NULL OR locked_until < NOW())
                        """.formatted(lock), workerId, id);
            }
            return rows;
        });
    }

    public boolean complete(long id, String workerId) {
        return mysqlJdbcTemplate.update("""
                UPDATE knowledge_search_outbox
                SET status='DONE', locked_by=NULL, locked_until=NULL, updated_at=NOW(), last_error=NULL
                WHERE id=? AND locked_by=?
                """, id, workerId) > 0;
    }

    public boolean fail(long id, String workerId, int attempts, boolean terminal,
                        LocalDateTime nextAvailable, String error) {
        return mysqlJdbcTemplate.update("""
                UPDATE knowledge_search_outbox
                SET status=?, attempts=?, available_at=?, locked_by=NULL, locked_until=NULL,
                    updated_at=?, last_error=?
                WHERE id=? AND locked_by=?
                """, terminal ? "FAILED" : "PENDING", attempts, nextAvailable,
                LocalDateTime.now(), error, id, workerId) > 0;
    }
}
