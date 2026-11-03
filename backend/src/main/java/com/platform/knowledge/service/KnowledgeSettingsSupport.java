package com.platform.knowledge.service;

import com.platform.common.exception.BusinessException;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** 知识库检索参数校验与轻量文本工具，便于主服务保持编排职责。 */
final class KnowledgeSettingsSupport {

    private KnowledgeSettingsSupport() {
    }

    static String normalizeBackend(String requested, String fallback, Set<String> allowed) {
        String value = requested == null || requested.isBlank() ? fallback : requested.trim();
        value = value == null ? "" : value.toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw new BusinessException(400, "不支持的检索后端：" + requested);
        }
        return value;
    }

    static String normalizeRetrievalMode(String mode) {
        String value = mode == null || mode.isBlank() ? "hybrid" : mode.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("hybrid", "vector", "keyword").contains(value)) {
            throw new BusinessException(400, "不支持的检索模式：" + mode);
        }
        return value;
    }

    static double normalizeScoreThreshold(Double value, double fallback) {
        if (value == null) return fallback;
        return Math.max(0, Math.min(1, value));
    }

    static double normalizeKeywordWeight(Double value, double fallback) {
        if (value == null) return fallback;
        return Math.max(0, Math.min(1, value));
    }

    static int normalizeCacheTtl(Integer value) {
        if (value == null) return 300;
        return Math.max(0, Math.min(value, 86400));
    }

    static int normalizeCacheMaxEntries(Integer value) {
        if (value == null) return 2000;
        return Math.max(0, Math.min(value, 100000));
    }

    static int[] normalizeTopK(Integer defaultTopK, Integer maxTopK) {
        int def = defaultTopK == null ? 5 : Math.max(1, Math.min(defaultTopK, 50));
        int max = maxTopK == null ? 20 : Math.max(1, Math.min(maxTopK, 50));
        if (max < def) max = def;
        return new int[]{def, max};
    }

    static String usersJson(List<Long> users) {
        // MySQL JSON_CONTAINS 与 PgVector jsonb_array_elements_text 都按字符串匹配 userId
        return users.stream().map(String::valueOf).map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    /** 图片说明按句末截断，避免切在句子中间。 */
    static String truncateAtSentence(String text, int max) {
        if (text == null || text.length() <= max) return text;
        int cut = max;
        for (int i = max - 1; i >= max / 2; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?'
                    || c == '；' || c == ';' || c == '\n') {
                cut = i + 1;
                break;
            }
        }
        return text.substring(0, cut).trim() + "…";
    }
}
