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

/** 按租户推进存量升级任务，每个周期每租户提交有限数量文档。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeUpgradeScheduler {

    private final SysTenantMapper tenantMapper;
    private final KnowledgeUpgradeService upgradeService;

    @Value("${knowledge.upgrade.batch:10}")
    private int batch = 10;

    @Scheduled(fixedDelayString = "${knowledge.upgrade.interval-ms:30000}",
            initialDelayString = "${knowledge.upgrade.initial-delay-ms:30000}")
    public void run() {
        List<SysTenant> tenants = tenantMapper.selectList(new LambdaQueryWrapper<SysTenant>()
                .in(SysTenant::getStatus, List.of("ACTIVE", "TRIAL")));
        if (tenants == null || tenants.isEmpty()) return;
        int submitted = 0;
        for (SysTenant tenant : tenants) {
            try {
                final Long tenantId = tenant.getId();
                submitted += TenantContext.runWith(tenantId,
                        () -> upgradeService.processTenant(batch));
            } catch (Exception e) {
                log.warn("存量升级任务推进失败 tenant={} err={}", tenant.getId(), e.getMessage());
            }
        }
        if (submitted > 0) log.info("存量升级任务提交文档：{}", submitted);
    }
}
