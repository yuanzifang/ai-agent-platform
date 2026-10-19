package com.platform.billing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.billing.entity.BillingUsage;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.billing.entity.BillingAccount;
import com.platform.billing.entity.BillingUsage;
import com.platform.billing.mapper.BillingAccountMapper;
import com.platform.common.mail.MailService;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.mapper.KnowledgeBaseMapper;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import com.platform.system.entity.SysUser;
import com.platform.system.mapper.SysUserMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.platform.system.entity.SysTenantQuota;
import com.platform.system.mapper.SysTenantQuotaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final SysUserMapper userMapper;
    private final MailService mailService;
    private final StringRedisTemplate redis;

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

    /** 预付费余额阻断：仅当租户发生过充值（account 存在）且可用余额 <= 0 时阻断。 */
    public void checkBalance() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) return;
        try {
            BillingAccount account = accountMapper.selectOne(new LambdaQueryWrapper<BillingAccount>()
                    .eq(BillingAccount::getTenantId, tenantId));
            if (account == null || account.getBalance() == null || account.getBalance().signum() <= 0) return;
            java.math.BigDecimal cost = java.math.BigDecimal.ZERO;
            for (BillingUsage u : usageMapper.selectList(new LambdaQueryWrapper<BillingUsage>()
                    .eq(BillingUsage::getTenantId, tenantId))) {
                cost = cost.add(u.getCost() == null ? java.math.BigDecimal.ZERO : u.getCost());
            }
            if (account.getBalance().subtract(cost).signum() <= 0) {
                throw new BusinessException(429, "账户余额不足，请充值后再调用");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception ignored) { }
    }

    /** 存储配额：当前租户附件大小 + 本次新增（知识库文档暂未计入）。 */
    public void checkStorage(long addBytes) {
        SysTenantQuota q = quota();
        if (q == null || q.getMaxStorageBytes() == null || q.getMaxStorageBytes() <= 0) return;
        long used = 0;
        for (com.platform.conversation.entity.ChatAttachment a
                : attachmentMapper.selectList(new LambdaQueryWrapper<>())) {
            used += a.getSizeBytes() == null ? 0 : a.getSizeBytes();
        }
        for (KnowledgeDocument d : documentMapper.selectList(new LambdaQueryWrapper<>())) {
            used += d.getSizeBytes() == null ? 0 : d.getSizeBytes();
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
        } catch (Exception ignored) { }
        int percent = (int) Math.min(100, used * 100 / max);
        log.warn("配额告警：租户 {} {} 使用率 {}%（{}/{}）", tenantId, metric, percent, used, max);
        try {
            for (SysUser u : userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getTenantId, tenantId).eq(SysUser::getStatus, "ENABLED"))) {
                if (u.getUsername() != null && u.getUsername().contains("@")
                        && userMapper.findRoleKeys(u.getId()).contains("OWNER")) {
                    mailService.send(u.getUsername(), "AI Agent 平台 - 配额告警",
                            metric + " 使用率已达 " + percent + "%（" + used + "/" + max + "），请及时调整配额或充值。");
                }
            }
        } catch (Exception e) {
            log.debug("配额告警邮件发送失败：{}", e.getMessage());
        }
    }

    public long tokensToday() {
        long used = 0;
        List<BillingUsage> rows = usageMapper.selectList(new LambdaQueryWrapper<BillingUsage>()
                .eq(BillingUsage::getStatDate, LocalDate.now()));
        for (BillingUsage u : rows) {
            used += (u.getInputTokens() == null ? 0 : u.getInputTokens())
                    + (u.getOutputTokens() == null ? 0 : u.getOutputTokens());
        }
        return used;
    }

    private SysTenantQuota quota() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) return null;
        return quotaMapper.selectOne(new LambdaQueryWrapper<SysTenantQuota>()
                .eq(SysTenantQuota::getTenantId, tenantId));
    }
}
