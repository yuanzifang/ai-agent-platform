package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.context.TenantContext;
import com.platform.system.entity.SysTenant;
import com.platform.system.mapper.SysTenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 定时为历史文档回填 source_sha256，使重复上传能命中整篇复用。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeHashBackfillScheduler {

    private final SysTenantMapper tenantMapper;
    private final KnowledgeService knowledgeService;

    @Value("${knowledge.hash-backfill.batch:20}")
    private int batch = 20;

    @Scheduled(fixedDelayString = "${knowledge.hash-backfill.interval-ms:600000}",
            initialDelayString = "${knowledge.hash-backfill.initial-delay-ms:60000}")
    public void run() {
        List<SysTenant> tenants = tenantMapper.selectList(new LambdaQueryWrapper<SysTenant>()
                .in(SysTenant::getStatus, List.of("ACTIVE", "TRIAL")));
        if (tenants == null || tenants.isEmpty()) return;
        int updated = 0;
        for (SysTenant tenant : tenants) {
            try {
                final Long tenantId = tenant.getId();
                Map<String, Object> result = TenantContext.runWith(tenantId,
                        () -> knowledgeService.backfillSourceHashesForTenant(batch));
                updated += result.get("updated") instanceof Number n ? n.intValue() : 0;
            } catch (Exception e) {
                log.warn("SHA256 定时回填失败 tenant={} err={}", tenant.getId(), e.getMessage());
            }
        }
        if (updated > 0) log.info("SHA256 定时回填完成：updated={}", updated);
    }
}
