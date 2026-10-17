package com.platform.conversation.service;

import com.platform.common.context.TenantContext;
import com.platform.conversation.entity.ModerationRecord;
import com.platform.conversation.mapper.ModerationRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 内容审核 P0（需求文档 3.21）：内置敏感词/注入攻击基础规则，命中后按策略处置并留痕；
 * 第三方审核服务为 P1 可插拔扩展点（接口见 moderate 返回结构）。
 */
@Slf4j
@Service
public class ModerationService {

    private final ModerationRecordMapper moderationMapper;
    private final List<String> blockWords;
    private final List<String> warnWords;
    private final List<String> injectionPatterns = List.of(
            "ignore previous instructions", "ignore all previous",
            "忽略之前所有指令", "忽略以上所有指令");

    public ModerationService(ModerationRecordMapper moderationMapper,
                             @Value("${moderation.block-words:赌博,毒品,代开发票}") List<String> blockWords,
                             @Value("${moderation.warn-words:转账,汇款,银行卡号}") List<String> warnWords) {
        this.moderationMapper = moderationMapper;
        this.blockWords = blockWords;
        this.warnWords = warnWords;
    }

    public record Hit(String ruleId, String action, String matchedText, String tip) {
    }

    /** direction: IN/OUT。返回命中列表（空为通过）；BLOCK 需拦截，WARN 放行但留痕。 */
    public List<Hit> moderate(String direction, String content, Long conversationId, Long messageId) {
        List<Hit> hits = new ArrayList<>();
        if (content == null || content.isBlank()) return hits;
        String lower = content.toLowerCase();
        for (String w : blockWords) {
            if (w != null && !w.isBlank() && lower.contains(w.toLowerCase())) {
                hits.add(new Hit("block-word:" + w, "BLOCK", w, "消息包含违规内容，已被拦截"));
            }
        }
        for (String w : warnWords) {
            if (w != null && !w.isBlank() && lower.contains(w.toLowerCase())) {
                hits.add(new Hit("warn-word:" + w, "WARN", w, "消息包含敏感内容，请注意合规"));
            }
        }
        for (String p : injectionPatterns) {
            if (lower.contains(p.toLowerCase())) {
                hits.add(new Hit("injection:" + p, "WARN", p, "检测到疑似提示注入内容，已记录"));
            }
        }
        for (Hit h : hits) {
            record(direction, h, conversationId, messageId);
        }
        return hits;
    }

    public boolean blocked(List<Hit> hits) {
        return hits.stream().anyMatch(h -> "BLOCK".equals(h.action()));
    }

    private void record(String direction, Hit h, Long conversationId, Long messageId) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            log.warn("无租户上下文，审核记录跳过留痕：{}", h.ruleId());
            return;
        }
        ModerationRecord r = new ModerationRecord();
        r.setDirection(direction);
        r.setMessageId(messageId);
        r.setConversationId(conversationId);
        r.setRuleId(h.ruleId());
        r.setAction(h.action());
        r.setMatchedText(mask(h.matchedText()));
        r.setTenantId(tenantId);
        r.setCreatedAt(LocalDateTime.now());
        moderationMapper.insert(r);
        log.info("审核命中 direction={} rule={} action={} conversation={}",
                direction, h.ruleId(), h.action(), conversationId);
    }

    /** 命中内容脱敏（截断保留前后各 2 字符）。 */
    private String mask(String text) {
        if (text == null || text.length() <= 4) return "***";
        return text.substring(0, 2) + "***" + text.substring(text.length() - 2);
    }

    public Map<String, Object> toEvent(Hit h) {
        return Map.of("action", h.action(), "ruleId", h.ruleId(), "tip", h.tip());
    }
}
