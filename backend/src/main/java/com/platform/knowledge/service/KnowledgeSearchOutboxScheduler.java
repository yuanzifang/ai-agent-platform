package com.platform.knowledge.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** ES 关键字索引 outbox worker。仅在 search.keyword.provider=es 时启用。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "search.es.enabled", havingValue = "true")
public class KnowledgeSearchOutboxScheduler {

    private final @Qualifier("jdbcTemplate") JdbcTemplate mysqlJdbcTemplate;
    private final KnowledgeSearchIndexService indexService;
    private final StringRedisTemplate redis;

    @Value("${search.es.bootstrap-on-start:true}")
    private boolean bootstrapOnStart = true;
    @Value("${search.es.outbox-batch:100}")
    private int batch = 100;
    @Value("${search.es.bootstrap-key:search:es:bootstrap:v1}")
    private String bootstrapKey = "search:es:bootstrap:v1";

    @Scheduled(fixedDelayString = "${search.es.outbox-interval-ms:2000}",
            initialDelayString = "${search.es.outbox-initial-delay-ms:5000}")
    public void run() {
        if (bootstrapOnStart) bootstrapOnce();
        List<Map<String, Object>> rows = mysqlJdbcTemplate.queryForList("""
                SELECT id, tenant_id, chunk_id, operation, attempts
                FROM knowledge_search_outbox
                WHERE status = 'PENDING' AND available_at <= NOW()
                ORDER BY id
                LIMIT ?
                """, Math.max(1, Math.min(batch, 1000)));
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            long tenantId = ((Number) row.get("tenant_id")).longValue();
            long chunkId = ((Number) row.get("chunk_id")).longValue();
            try {
                if ("DELETE".equals(String.valueOf(row.get("operation")))) {
                    indexService.deleteChunk(tenantId, chunkId);
                } else {
                    indexService.indexChunk(tenantId, chunkId);
                }
                mysqlJdbcTemplate.update("""
                        UPDATE knowledge_search_outbox
                        SET status='DONE', updated_at=NOW(), last_error=NULL
                        WHERE id=?
                        """, id);
            } catch (Exception e) {
                int attempts = row.get("attempts") instanceof Number n ? n.intValue() + 1 : 1;
                LocalDateTime next = LocalDateTime.now().plusSeconds(Math.min(300, attempts * 10L));
                mysqlJdbcTemplate.update("""
                        UPDATE knowledge_search_outbox
                        SET status='PENDING', attempts=?, available_at=?, updated_at=?, last_error=?
                        WHERE id=?
                        """, attempts, next, LocalDateTime.now(),
                        truncate(e.getMessage()), id);
                log.warn("ES outbox 索引失败 id={} chunk={} err={}", id, chunkId, e.getMessage());
            }
        }
    }

    private void bootstrapOnce() {
        try {
            Boolean first = redis.opsForValue().setIfAbsent(bootstrapKey, "1", Duration.ofDays(30));
            if (Boolean.TRUE.equals(first)) {
                int queued = indexService.enqueueAll();
                log.info("ES 首次启动 bootstrap：已排队 {} 个分段", queued);
            }
        } catch (Exception e) {
            log.warn("ES bootstrap 跳过：{}", e.getMessage());
        }
    }

    private String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
