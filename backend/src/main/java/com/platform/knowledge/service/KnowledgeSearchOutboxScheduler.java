package com.platform.knowledge.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ES 关键字索引 outbox worker。仅在 search.keyword.provider=es 时启用。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "search.es.enabled", havingValue = "true")
public class KnowledgeSearchOutboxScheduler {

    private final KnowledgeSearchIndexService indexService;
    private final SearchOutboxClaimService claimService;
    private final StringRedisTemplate redis;

    private static final String WORKER_ID = workerId();

    @Value("${search.es.bootstrap-on-start:true}")
    private boolean bootstrapOnStart = true;
    @Value("${search.es.outbox-batch:100}")
    private int batch = 100;
    @Value("${search.es.outbox-lock-seconds:120}")
    private int lockSeconds = 120;
    @Value("${search.es.bootstrap-key:search:es:bootstrap:v1}")
    private String bootstrapKey = "search:es:bootstrap:v1";

    @Scheduled(fixedDelayString = "${search.es.outbox-interval-ms:2000}",
            initialDelayString = "${search.es.outbox-initial-delay-ms:5000}")
    public void run() {
        if (bootstrapOnStart) bootstrapOnce();
        List<Map<String, Object>> rows = claimService.claim(WORKER_ID, batch, lockSeconds);
        if (!rows.isEmpty()) {
            log.info("ES outbox 认领 {} 条 worker={}", rows.size(), WORKER_ID);
        }
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
                if (!claimService.complete(id, WORKER_ID)) {
                    log.debug("ES outbox 完成时锁已失效 id={} worker={}", id, WORKER_ID);
                }
            } catch (Exception e) {
                int attempts = row.get("attempts") instanceof Number n ? n.intValue() + 1 : 1;
                boolean terminal = attempts >= 5;
                LocalDateTime next = LocalDateTime.now().plusSeconds(Math.min(300, attempts * 10L));
                if (!claimService.fail(id, WORKER_ID, attempts, terminal, next, truncate(e.getMessage()))) {
                    log.debug("ES outbox 失败时锁已失效 id={} worker={}", id, WORKER_ID);
                }
                log.warn("ES outbox 索引失败 id={} chunk={} worker={} err={}",
                        id, chunkId, WORKER_ID, e.getMessage());
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

    private static String workerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
