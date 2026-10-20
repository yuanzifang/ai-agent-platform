package com.platform.conversation.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 内置关键词兜底：屏蔽词 / 告警词 / 提示注入特征。 */
@Slf4j
@Component
public class KeywordModerationProvider implements ContentModerationProvider {

    private final List<String> blockWords;
    private final List<String> warnWords;
    private final List<String> injectionPatterns = List.of(
            "ignore previous instructions", "ignore all previous",
            "忽略之前所有指令", "忽略以上所有指令");

    public KeywordModerationProvider(
            @Value("${moderation.block-words:赌博,毒品,代开发票}") List<String> blockWords,
            @Value("${moderation.warn-words:转账,汇款,银行卡号}") List<String> warnWords) {
        this.blockWords = blockWords;
        this.warnWords = warnWords;
    }

    @Override
    public String name() {
        return "keyword";
    }

    @Override
    public List<ModerationService.Hit> check(String direction, String content) {
        List<ModerationService.Hit> hits = new ArrayList<>();
        if (content == null || content.isBlank()) return hits;
        String lower = content.toLowerCase();
        for (String w : blockWords) {
            if (w != null && !w.isBlank() && lower.contains(w.toLowerCase())) {
                hits.add(new ModerationService.Hit("block-word:" + w, "BLOCK", w,
                        "消息包含违规内容，已被拦截"));
            }
        }
        for (String w : warnWords) {
            if (w != null && !w.isBlank() && lower.contains(w.toLowerCase())) {
                hits.add(new ModerationService.Hit("warn-word:" + w, "WARN", w,
                        "消息包含敏感内容，请注意合规"));
            }
        }
        for (String p : injectionPatterns) {
            if (lower.contains(p.toLowerCase())) {
                hits.add(new ModerationService.Hit("injection:" + p, "WARN", p,
                        "检测到疑似提示注入内容，已记录"));
            }
        }
        return hits;
    }
}
