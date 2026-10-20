package com.platform.conversation.service;

import com.platform.common.context.TenantContext;
import com.platform.conversation.entity.ModerationRecord;
import com.platform.conversation.mapper.ModerationRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容审核（需求 3.21）：可插拔 Provider（内置关键词兜底 + 第三方 HTTP/私有实现），
 * 命中后按策略处置并留痕；同一规则/命中文本去重。
 */
@Slf4j
@Service
public class ModerationService {

    private final ModerationRecordMapper moderationMapper;
    private final List<ContentModerationProvider> providers;

    public ModerationService(ModerationRecordMapper moderationMapper,
                             List<ContentModerationProvider> providers) {
        this.moderationMapper = moderationMapper;
        this.providers = providers == null ? List.of() : providers;
    }

    public record Hit(String ruleId, String action, String matchedText, String tip) {
    }

    /** direction: IN/OUT。返回命中列表（空为通过）；BLOCK 拦截，WARN 放行但留痕。 */
    public List<Hit> moderate(String direction, String content, Long conversationId, Long messageId) {
        if (content == null || content.isBlank()) return List.of();
        Map<String, Hit> unique = new LinkedHashMap<>();
        for (ContentModerationProvider provider : providers) {
            try {
                List<Hit> hits = provider.check(direction, content);
                if (hits == null) continue;
                for (Hit hit : hits) {
                    if (hit == null) continue;
                    unique.putIfAbsent(hit.ruleId() + "|" + hit.matchedText(), hit);
                }
            } catch (Exception e) {
                log.warn("审核 Provider「{}」异常（放行）：{}", provider.name(), e.getMessage());
            }
        }
        List<Hit> hits = new ArrayList<>(unique.values());
        for (Hit h : hits) {
            record(direction, h, conversationId, messageId);
        }
        return hits;
    }

    public boolean blocked(List<Hit> hits) {
        return hits != null && hits.stream().anyMatch(h -> "BLOCK".equals(h.action()));
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

    private String mask(String text) {
        if (text == null || text.length() <= 4) return "***";
        return text.substring(0, 2) + "***" + text.substring(text.length() - 2);
    }

    public Map<String, Object> toEvent(Hit h) {
        return Map.of("action", h.action(), "ruleId", h.ruleId(), "tip", h.tip());
    }
}
