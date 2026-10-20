package com.platform.eval.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.context.TenantContext;
import com.platform.system.entity.SysConfig;
import com.platform.system.entity.SysTenant;
import com.platform.system.mapper.SysConfigMapper;
import com.platform.system.mapper.SysTenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 在线监控定时抽样（需求 3.5 / 架构 7.5）：默认每小时扫描最近 1 小时的 SUCCESS Trace，
 * 按 sys_config「eval.sample-rate」（默认 10%）抽样，逐租户/逐 Agent 创建 ONLINE 评测任务。
 * 多实例用 Redis SETNX 互斥；cron 可经 eval.online.cron 覆盖（6 位 Spring cron）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvalOnlineScheduler {

    private static final String LOCK_KEY = "lock:eval:online:hourly";

    private final EvalService evalService;
    private final SysConfigMapper sysConfigMapper;
    private final SysTenantMapper tenantMapper;
    private final StringRedisTemplate redis;

    @Scheduled(cron = "${eval.online.cron:0 10 * * * *}")
    public void hourlyOnlineMonitor() {
        Boolean locked = redis.opsForValue().setIfAbsent(LOCK_KEY, "1", Duration.ofMinutes(50));
        if (!Boolean.TRUE.equals(locked)) {
            log.info("在线监控抽样跳过：已有实例在执行");
            return;
        }
        try {
            double rate = sampleRate();
            List<SysTenant> tenants = tenantMapper.selectList(new LambdaQueryWrapper<SysTenant>()
                    .in(SysTenant::getStatus, List.of("ACTIVE", "TRIAL")));
            int created = 0;
            for (SysTenant tenant : tenants) {
                try {
                    final Long tid = tenant.getId();
                    created += TenantContext.runWith(tid, () -> evalService.runOnlineSampling(tid, rate));
                } catch (Exception e) {
                    log.warn("租户 {} 在线监控抽样失败：{}", tenant.getTenantKey(), e.getMessage());
                }
            }
            log.info("在线监控抽样完成：租户 {} 个，抽样比例 {}，创建任务 {} 个", tenants.size(), rate, created);
        } finally {
            redis.delete(LOCK_KEY);
        }
    }

    private double sampleRate() {
        SysConfig cfg = sysConfigMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, "eval.sample-rate"));
        if (cfg != null && cfg.getConfigValue() != null) {
            try { return Math.max(0, Math.min(1, Double.parseDouble(cfg.getConfigValue()))); }
            catch (Exception e) {     log.debug("在线抽样比例解析失败，使用默认值：{}", e.getMessage()); }
        }
        return 0.1;
    }
}
