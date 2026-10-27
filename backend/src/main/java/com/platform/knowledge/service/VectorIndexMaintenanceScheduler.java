package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.context.TenantContext;
import com.platform.system.entity.SysTenant;
import com.platform.system.mapper.SysTenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

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

    private final SysTenantMapper tenantMapper;
    private final VectorRepository vectorRepository;

    @Scheduled(fixedDelayString =
            "${pgvector.hnsw.partial.reconcile-interval-ms:86400000}",
            initialDelayString =
                    "${pgvector.hnsw.partial.initial-delay-ms:300000}")
    public void run() {
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
    }
}
