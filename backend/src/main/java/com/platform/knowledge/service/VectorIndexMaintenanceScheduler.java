package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.context.TenantContext;
import com.platform.system.entity.SysTenant;
import com.platform.system.mapper.SysTenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 周期性维护按知识库拆分的 partial HNSW 索引。
 *
 * <p>只在知识库行数达到阈值时建索引，且每张维度表最多维护配置数量的索引；
 * 索引创建使用 CONCURRENTLY（按配置），不阻塞在线检索。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorIndexMaintenanceScheduler {

    private static final String LOCK_KEY = "lock:vector:partial:reconcile";
    private static final Duration LOCK_TTL = Duration.ofHours(1);
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            else
              return 0
            end
            """, Long.class);

    private final SysTenantMapper tenantMapper;
    private final VectorStore vectorRepository;
    private final StringRedisTemplate redis;

    @Scheduled(fixedDelayString =
            "${pgvector.hnsw.partial.reconcile-interval-ms:86400000}",
            initialDelayString =
                    "${pgvector.hnsw.partial.initial-delay-ms:300000}")
    public void run() {
        String token = UUID.randomUUID().toString();
        boolean locked;
        try {
            locked = Boolean.TRUE.equals(redis.opsForValue()
                    .setIfAbsent(LOCK_KEY, token, LOCK_TTL));
        } catch (Exception e) {
            // Redis 不可用时不阻断维护；CREATE INDEX IF NOT EXISTS 仍提供幂等兜底。
            log.warn("partial HNSW 维护锁不可用，继续执行：{}", e.getMessage());
            locked = true;
        }
        if (!locked) {
            log.info("partial HNSW 维护跳过：已有实例在执行");
            return;
        }
        try {
            List<SysTenant> tenants = tenantMapper.selectList(new LambdaQueryWrapper<SysTenant>()
                    .in(SysTenant::getStatus, List.of("ACTIVE", "TRIAL")));
            if (tenants == null || tenants.isEmpty()) return;
            for (SysTenant tenant : tenants) {
                try {
                    Map<String, Integer> stats = TenantContext.runWith(tenant.getId(),
                            () -> vectorRepository.reconcilePartialIndexes(tenant.getId()));
                    if (stats.getOrDefault("created", 0) > 0
                            || stats.getOrDefault("dropped", 0) > 0) {
                        log.info("partial HNSW 维护完成 tenant={} {}", tenant.getId(), stats);
                    }
                } catch (Exception e) {
                    log.warn("partial HNSW 维护失败 tenant={} err={}", tenant.getId(), e.getMessage());
                }
            }
        } finally {
            try {
                redis.execute(UNLOCK_SCRIPT, List.of(LOCK_KEY), token);
            } catch (Exception e) {
                log.warn("partial HNSW 维护锁释放失败：{}", e.getMessage());
            }
        }
    }
}
