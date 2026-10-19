package com.platform.billing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.billing.entity.BillingUsage;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.mapper.KnowledgeBaseMapper;
import com.platform.system.entity.SysTenantQuota;
import com.platform.system.mapper.SysTenantQuotaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 配额校验（需求 3.11(4)）：0 表示不限制；超限拒绝创建/调用。
 * Agent/知识库/每日 Token；存储与每分钟调用在相关入口按可用数据校验。
 */
@Service
@RequiredArgsConstructor
public class QuotaService {

    private final SysTenantQuotaMapper quotaMapper;
    private final AgentRegistryMapper agentMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final BillingUsageMapper usageMapper;
    private final com.platform.conversation.mapper.ChatAttachmentMapper attachmentMapper;

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

    /** 每日 Token 配额：已用量 >= 上限时拒绝新调用（预估本次 Token 仅用于提示）。 */
    public void checkTokensToday(long estimatedTokens) {
        SysTenantQuota q = quota();
        if (q == null || q.getMaxTokensPerDay() == null || q.getMaxTokensPerDay() <= 0) return;
        long used = tokensToday();
        if (used >= q.getMaxTokensPerDay()) {
            throw new BusinessException(429, "今日 Token 配额已用尽（" + used + "/" + q.getMaxTokensPerDay() + "），请明日再试或调整配额");
        }
        if (used + Math.max(estimatedTokens, 0) > q.getMaxTokensPerDay()) {
            throw new BusinessException(429, "本次调用将超出今日 Token 配额（已用 " + used + "/"
                    + q.getMaxTokensPerDay() + "），请调整配额或明天再试");
        }
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
        if (used + Math.max(addBytes, 0) > q.getMaxStorageBytes()) {
            throw new BusinessException(429, "存储配额不足（已用 " + used + " / " + q.getMaxStorageBytes()
                    + " 字节），请清理附件或调整配额");
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
