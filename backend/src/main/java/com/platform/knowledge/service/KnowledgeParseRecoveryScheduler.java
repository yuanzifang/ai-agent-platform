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

/**
 * 定时回收因进程崩溃/重启而长期停在 PARSING 的文档，清理 MySQL 分段/父块和 PgVector 残留。
 * 多实例部署时由条件更新保证同一文档只有一个实例执行清理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeParseRecoveryScheduler {

    private final SysTenantMapper tenantMapper;
    private final KnowledgeService knowledgeService;

    @Value("${knowledge.parse.stale-minutes:360}")
    private int staleMinutes = 360;
    @Value("${knowledge.parse.recovery-batch:20}")
    private int recoveryBatch = 20;

    @Scheduled(fixedDelayString = "${knowledge.parse.recovery-interval-ms:1800000}",
            initialDelayString = "${knowledge.parse.recovery-initial-delay-ms:300000}")
    public void recover() {
        List<SysTenant> tenants = tenantMapper.selectList(new LambdaQueryWrapper<SysTenant>()
                .in(SysTenant::getStatus, List.of("ACTIVE", "TRIAL")));
        if (tenants == null || tenants.isEmpty()) return;
        int total = 0;
        for (SysTenant tenant : tenants) {
            try {
                final Long tenantId = tenant.getId();
                total += TenantContext.runWith(tenantId,
                        () -> knowledgeService.recoverStaleParsing(staleMinutes, recoveryBatch));
            } catch (Exception e) {
                log.warn("回收租户超时解析失败 tenant={} err={}", tenant.getId(), e.getMessage());
            }
        }
        if (total > 0) log.warn("超时解析回收完成：documents={} tenants={}", total, tenants.size());
    }
}
