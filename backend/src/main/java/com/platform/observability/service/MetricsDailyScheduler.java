package com.platform.observability.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.context.TenantContext;
import com.platform.observability.entity.AgentMetricsDaily;
import com.platform.observability.entity.AgentTrace;
import com.platform.observability.mapper.AgentMetricsDailyMapper;
import com.platform.observability.mapper.AgentTraceMapper;
import com.platform.system.entity.SysTenant;
import com.platform.system.mapper.SysTenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 监控日汇总任务（需求 6.13 / 架构 7.3）：每日生成前一天的可加性指标到 agent_metrics_daily。
 * 多实例用 Redis SETNX 互斥；cron 可经 metrics.daily.cron 覆盖（6 位 Spring cron）。
 * 日表缺失的日期由查询侧回落实时明细，任务失败不丢数据（一致性优先）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsDailyScheduler {

    private static final String LOCK_KEY = "lock:metrics:daily";

    private final AgentTraceMapper traceMapper;
    private final AgentMetricsDailyMapper dailyMapper;
    private final SysTenantMapper tenantMapper;
    private final StringRedisTemplate redis;

    @Scheduled(cron = "${metrics.daily.cron:0 20 0 * * *}")
    public void aggregateYesterday() {
        Boolean locked = redis.opsForValue().setIfAbsent(LOCK_KEY, "1", Duration.ofHours(2));
        if (!Boolean.TRUE.equals(locked)) {
            log.info("监控日汇总跳过：已有实例在执行");
            return;
        }
        try {
            LocalDate day = LocalDate.now().minusDays(1);
            List<SysTenant> tenants = tenantMapper.selectList(new LambdaQueryWrapper<SysTenant>()
                    .in(SysTenant::getStatus, List.of("ACTIVE", "TRIAL")));
            int rows = 0;
            for (SysTenant tenant : tenants) {
                try {
                    final Long tid = tenant.getId();
                    rows += TenantContext.runWith(tid, () -> aggregateTenant(tid, day));
                } catch (Exception e) {
                    log.warn("租户 {} 监控日汇总失败：{}", tenant.getTenantKey(), e.getMessage());
                }
            }
            log.info("监控日汇总完成 date={} 租户 {} 个 写入 {} 行", day, tenants.size(), rows);
        } finally {
            redis.delete(LOCK_KEY);
        }
    }

    /** 汇总单租户单日：按当日有 Trace 的 Agent 逐条 upsert（唯一键 stat_date+tenant_id+agent_id）。 */
    private int aggregateTenant(Long tenantId, LocalDate day) {
        LocalDateTime dayStart = day.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        List<Long> agentIds = traceMapper.selectList(new LambdaQueryWrapper<AgentTrace>()
                        .select(AgentTrace::getAgentId)
                        .ge(AgentTrace::getCreatedAt, dayStart)
                        .lt(AgentTrace::getCreatedAt, dayEnd)
                        .isNotNull(AgentTrace::getAgentId))
                .stream().map(AgentTrace::getAgentId).distinct().toList();

        int written = 0;
        for (Long agentId : agentIds) {
            long calls = 0;
            long tokens = 0;
            long errors = 0;
            BigDecimal cost = BigDecimal.ZERO;
            for (Map<String, Object> row : traceMapper.dailyAgg(agentId, dayStart, dayEnd)) {
                calls += num(row.get("calls"));
                tokens += num(row.get("tokens"));
                errors += num(row.get("errorCount"));
                cost = cost.add(dec(row.get("cost")));
            }
            long users = 0;
            for (Map<String, Object> row : traceMapper.dailyDistinctUsers(agentId, dayStart, dayEnd)) {
                users += num(row.get("users"));
            }
            long sessions = traceMapper.firstDaySessions(agentId, day);

            AgentMetricsDaily existing = dailyMapper.selectOne(new LambdaQueryWrapper<AgentMetricsDaily>()
                    .eq(AgentMetricsDaily::getAgentId, agentId)
                    .eq(AgentMetricsDaily::getStatDate, day));
            AgentMetricsDaily row = existing == null ? new AgentMetricsDaily() : existing;
            row.setStatDate(day);
            row.setTenantId(tenantId);
            row.setAgentId(agentId);
            row.setCost(cost);
            row.setCalls(calls);
            row.setSessions(sessions);
            row.setActiveUsers(users);
            row.setTokens(tokens);
            row.setErrorCount(errors);
            if (existing == null) {
                row.setCreatedAt(LocalDateTime.now());
                dailyMapper.insert(row);
            } else {
                dailyMapper.updateById(row);
            }
            written++;
        }
        return written;
    }

    private static long num(Object v) {
        return v == null ? 0 : ((Number) v).longValue();
    }

    private static BigDecimal dec(Object v) {
        if (v == null) return BigDecimal.ZERO;
        return v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v));
    }
}
