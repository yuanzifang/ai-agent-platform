package com.platform.billing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.billing.entity.BillingAccount;
import com.platform.billing.entity.BillingUsage;
import com.platform.billing.mapper.BillingAccountMapper;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.common.mail.MailService;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.mapper.KnowledgeBaseMapper;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import com.platform.system.entity.SysUser;
import com.platform.system.entity.SysTenantQuota;
import com.platform.system.mapper.SysTenantQuotaMapper;
import com.platform.system.mapper.SysUserMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配额校验（需求 3.11(4)）：0 表示不限制；超限拒绝创建/调用。
 * Agent/知识库/每日 Token；存储与每分钟调用在相关入口按可用数据校验。
 */
@Service
@RequiredArgsConstructor
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final SysTenantQuotaMapper quotaMapper;
    private final AgentRegistryMapper agentMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final BillingUsageMapper usageMapper;
    private final com.platform.conversation.mapper.ChatAttachmentMapper attachmentMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final BillingAccountMapper accountMapper;
    private final com.platform.system.service.AlertWebhookService alertWebhook;
    private final SysUserMapper userMapper;
    private final com.platform.system.mapper.UserQuotaMapper userQuotaMapper;
    private final MailService mailService;
    private final StringRedisTemplate redis;
    private final MeterRegistry meterRegistry;

    private static final long QUOTA_CACHE_MS = 30_000;
    private static final long TOKEN_CACHE_MS = 1_000;
    private final Map<Long, CachedQuota> quotaCache = new ConcurrentHashMap<>();
    private final Map<Long, CachedTokens> tokenCache = new ConcurrentHashMap<>();

    public void checkAgentCreate() {
        SysTenantQuota q = quota();
        if (q == null || q.getMaxAgents() == null || q.getMaxAgents() <= 0) return;
        long count = agentMapper.selectCount(new LambdaQueryWrapper<AgentRegistry>()
                .ne(AgentRegistry::getStatus, "DELETED"));
        if (count >= q.getMaxAgents()) {
            throw new BusinessException(400, "Agent 数量已达配额上限（" + q.getMaxAgents() + "），请联系管理员调整配额");
        }
    }

    public void checkKnowledgeBaseCreate() {
        SysTenantQuota q = quota();
        if (q == null || q.getMaxKnowledgeBases() == null || q.getMaxKnowledgeBases() <= 0) return;
        long count = kbMapper.selectCount(new LambdaQueryWrapper<KnowledgeBase>());
        if (count >= q.getMaxKnowledgeBases()) {
            throw new BusinessException(400, "知识库数量已达配额上限（" + q.getMaxKnowledgeBases() + "），请联系管理员调整配额");
        }
    }

    /** 每日 Token 配额 + 预付费余额：任一不足即拒绝（预估本次 Token 仅用于提示）。 */
    public void checkTokensToday(long estimatedTokens) {
        checkBalance();
        SysTenantQuota q = quota();
        if (q == null || q.getMaxTokensPerDay() == null || q.getMaxTokensPerDay() <= 0) return;
        long used = tokensToday();
        long max = q.getMaxTokensPerDay();
        if (used >= max) {
            alertQuota("每日Token", used, max);
            throw new BusinessException(429, "今日 Token 配额已用尽（" + used + "/" + max + "），请明日再试或调整配额");
        }
        if (used + Math.max(estimatedTokens, 0) > max) {
            alertQuota("每日Token", used, max);
            throw new BusinessException(429, "本次调用将超出今日 Token 配额（已用 " + used + "/"
                    + max + "），请调整配额或明天再试");
        }
        alertQuota("每日Token", used, max);
    }

    /**
     * 人员维度配额：每日 Token 上限 + 本月消费上限；未配置/disabled 时不限制。
     * 只校验已发生用量与本次预估，成本估算仍由租户余额/模型计价负责。
     */
    public void checkUserQuota(Long userId, long estimatedTokens) {
        if (userId == null) return;
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) return;
        com.platform.system.entity.UserQuota q = userQuotaMapper.selectOne(
                new LambdaQueryWrapper<com.platform.system.entity.UserQuota>()
                        .eq(com.platform.system.entity.UserQuota::getTenantId, tenantId)
                        .eq(com.platform.system.entity.UserQuota::getUserId, userId));
        if (q == null || (q.getEnabled() != null && q.getEnabled() == 0)) return;
        if (q.getDailyTokenLimit() != null && q.getDailyTokenLimit() > 0) {
            long used = usageMapper.sumTokensTodayByUser(userId);
            long max = q.getDailyTokenLimit();
            if (used >= max || used + Math.max(estimatedTokens, 0) > max) {
                throw new BusinessException(429, "个人今日 Token 配额不足（已用 " + used + "/" + max + "），请联系管理员调整");
            }
        }
        if (q.getMonthlyCostLimit() != null && q.getMonthlyCostLimit().signum() > 0) {
            java.math.BigDecimal used = usageMapper.sumMonthCostByUser(userId,
                    LocalDate.now().withDayOfMonth(1));
            if (used.compareTo(q.getMonthlyCostLimit()) >= 0) {
                throw new BusinessException(429, "个人本月消费配额已用尽（" + used + "/"
                        + q.getMonthlyCostLimit() + "），请联系管理员调整");
            }
        }
    }

        /**
     * 租户月度金额上限（评审：金额是跨模型的统一度量，Token 数不是）。
     * 本月 billing_usage.cost 汇总 vs sys_tenant_quota.monthly_cost_limit；未配置 = 不限。
     */
    public void checkMonthlyCost() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) return;
        SysTenantQuota q = quota();
        if (q == null || q.getMonthlyCostLimit() == null || q.getMonthlyCostLimit().signum() <= 0) return;
        java.math.BigDecimal monthCost = usageMapper.sumMonthCostByTenant(
                tenantId, java.time.LocalDate.now().withDayOfMonth(1));
        if (monthCost.compareTo(q.getMonthlyCostLimit()) >= 0) {
            alertQuota("月度金额", monthCost.movePointRight(0).longValue(), q.getMonthlyCostLimit().longValue());
            throw new BusinessException(429, "本月消费已达上限（" + monthCost + "/" + q.getMonthlyCostLimit()
                    + " 元），请调整配额或联系管理员");
        }
    }

    /** 预付费余额阻断：仅当租户发生过充值（account 存在）且可用余额 <= 0 时阻断。
     * 成本用 SQL 聚合（曾全表拉取到内存求和，量级增长后拖垮每次对话）。 */
    public void checkBalance() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) return;
        try {
            BillingAccount account = accountMapper.selectOne(new LambdaQueryWrapper<BillingAccount>()
                    .eq(BillingAccount::getTenantId, tenantId));
            if (account == null || account.getBalance() == null || account.getBalance().signum() <= 0) return;
            java.math.BigDecimal cost = usageMapper.sumCostByTenant(tenantId);
            if (account.getBalance().subtract(cost).signum() <= 0) {
                throw new BusinessException(429, "账户余额不足，请充值后再调用");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // fail-open 但必须可见：余额校验故障静默放行会造成资损，记录告警日志 + 指标
            meterRegistry.counter("platform.quota.balance_check_failed").increment();
            log.error("余额校验异常（已放行，请排查）：tenant={} err={}", tenantId, e.getMessage(), e);
        }
    }

    /** 存储配额：租户附件+文档占用（SQL 聚合）+ 本次新增。 */
    public void checkStorage(long addBytes) {
        SysTenantQuota q = quota();
        if (q == null || q.getMaxStorageBytes() == null || q.getMaxStorageBytes() <= 0) return;
        Long tenantId = TenantContext.getTenantId();
        long used = 0;
        if (tenantId != null) {
            used = attachmentMapper.sumSizeByTenant(tenantId) + documentMapper.sumSizeByTenant(tenantId);
        }
        long max = q.getMaxStorageBytes();
        if (used + Math.max(addBytes, 0) > max) {
            alertQuota("存储", used, max);
            throw new BusinessException(429, "存储配额不足（已用 " + used + " / " + max
                    + " 字节），请清理附件/文档或调整配额");
        }
        alertQuota("存储", used, max);
    }

    /** 80% 阈值告警：日志 + 租户所有者邮件（Redis 按天去重，未配置 SMTP 时仅日志）。 */
    private void alertQuota(String metric, long used, long max) {
        if (max <= 0 || used * 100 < max * 80) return;
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) return;
        String key = "quota:alert:" + metric + ":" + tenantId + ":" + LocalDate.now();
        try {
            Boolean first = redis.opsForValue().setIfAbsent(key, "1", java.time.Duration.ofHours(25));
            if (!Boolean.TRUE.equals(first)) return;
        } catch (Exception e) {
            log.warn("配额告警去重失败（继续发送）：{}", e.getMessage());
        }
        int percent = (int) Math.min(100, used * 100 / max);
        log.warn("配额告警：租户 {} {} 使用率 {}%（{}/{}）", tenantId, metric, percent, used, max);
        alertWebhook.alert("配额告警：租户 " + tenantId + " 的 " + metric + " 已用 " + percent + "%",
                metric + " " + used + "/" + max + "，请调整配额或充值。");
        try {
            for (SysUser u : userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getTenantId, tenantId).eq(SysUser::getStatus, "ENABLED"))) {
                if (u.getUsername() != null && u.getUsername().contains("@")
                        && userMapper.findRoleKeys(u.getId()).contains("OWNER")) {
                    mailService.sendLocalized(u.getUsername(), u.getLocale(),
                            "AI Agent 平台 - 配额告警",
                            metric + " 使用率已达 " + percent + "%（" + used + "/" + max + "），请及时调整配额或充值。",
                            "AI Agent Platform - Quota alert",
                            metric + " usage has reached " + percent + "% (" + used + "/" + max
                                    + "). Adjust the quota or recharge.");
                }
            }
        } catch (Exception e) {
            log.debug("配额告警邮件发送失败：{}", e.getMessage());
        }
    }

    public long tokensToday() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) return 0;
        long now = System.currentTimeMillis();
        CachedTokens cached = tokenCache.get(tenantId);
        if (cached != null && cached.expiresAt() > now) return cached.value();
        long used = usageMapper.sumTokensToday(tenantId);
        tokenCache.put(tenantId, new CachedTokens(used, now + TOKEN_CACHE_MS));
        return used;
    }

    /** 用量落库后同步本地计数，避免等待下一次数据库聚合。 */
    public void addTokens(Long tenantId, long tokens) {
        if (tenantId == null || tokens <= 0) return;
        tokenCache.computeIfPresent(tenantId, (id, cached) ->
                new CachedTokens(cached.value() + tokens, cached.expiresAt()));
    }

    private SysTenantQuota quota() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) return null;
        long now = System.currentTimeMillis();
        CachedQuota cached = quotaCache.get(tenantId);
        if (cached != null && cached.expiresAt() > now) return cached.quota();
        SysTenantQuota value = quotaMapper.selectOne(new LambdaQueryWrapper<SysTenantQuota>()
                .eq(SysTenantQuota::getTenantId, tenantId));
        if (value != null) quotaCache.put(tenantId, new CachedQuota(value, now + QUOTA_CACHE_MS));
        return value;
    }

    private record CachedQuota(SysTenantQuota quota, long expiresAt) {
    }

    private record CachedTokens(long value, long expiresAt) {
    }
}
