package com.platform.model.service;

import org.springframework.ai.chat.metadata.Usage;

import java.lang.reflect.Method;

/**
 * 统一抽取 usage 分项：缓存命中/写入、推理 Token。
 * 兼容 OpenAI（prompt_tokens_details.cached_tokens）与 Anthropic（cache_*_input_tokens）。
 * 厂商未返回时字段为 0，不影响原有输入/输出计价。
 */
public record UsageMetrics(long inputTokens, long outputTokens,
                           long cachedInputTokens, long cacheWriteTokens, long reasoningTokens) {

    public static UsageMetrics of(Usage usage) {
        if (usage == null) return new UsageMetrics(0, 0, 0, 0, 0);
        long input = nz(usage.getPromptTokens());
        long output = nz(usage.getCompletionTokens());
        Object nativeUsage = usage.getNativeUsage();
        long cached = 0;
        long cacheWrite = 0;
        long reasoning = 0;
        if (nativeUsage != null) {
            Object promptDetails = invoke(nativeUsage, "promptTokensDetails");
            cached = firstNonZero(
                    longValue(promptDetails, "cachedTokens"),
                    longValue(nativeUsage, "promptCacheHitTokens"),
                    longValue(nativeUsage, "prompt_cache_hit_tokens"),
                    longValue(nativeUsage, "cache_read_input_tokens"));
            cacheWrite = firstNonZero(
                    longValue(nativeUsage, "cacheCreationInputTokens"),
                    longValue(nativeUsage, "cache_creation_input_tokens"),
                    longValue(nativeUsage, "cacheWriteTokens"));
            Object completionDetails = invoke(nativeUsage, "completionTokenDetails");
            reasoning = firstNonZero(
                    longValue(completionDetails, "reasoningTokens"),
                    longValue(nativeUsage, "reasoningTokens"),
                    longValue(nativeUsage, "reasoning_tokens"));
        }
        return new UsageMetrics(input, output, Math.min(cached, input), cacheWrite, reasoning);
    }

    private static Object invoke(Object target, String method) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Exception e) {
            return null;
        }
    }

    private static long longValue(Object target, String fieldOrMethod) {
        if (target == null) return 0;
        Object value = invoke(target, fieldOrMethod);
        if (value instanceof Number n) return n.longValue();
        return 0;
    }

    private static long firstNonZero(long... values) {
        for (long value : values) {
            if (value > 0) return value;
        }
        return 0;
    }

    private static long nz(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
