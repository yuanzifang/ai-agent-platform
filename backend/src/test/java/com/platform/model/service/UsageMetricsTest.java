package com.platform.model.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.Usage;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 从 Spring AI native usage 的反射字段提取缓存/推理分项。 */
class UsageMetricsTest {

    record PromptDetails(Integer cachedTokens) {
        public Integer cachedTokens() {
            return cachedTokens;
        }
    }

    record CompletionDetails(Integer reasoningTokens) {
        public Integer reasoningTokens() {
            return reasoningTokens;
        }
    }

    record NativeUsage(PromptDetails promptTokensDetails, CompletionDetails completionTokenDetails) {
    }

    @Test
    void extractsCachedAndReasoningTokens() {
        NativeUsage nativeUsage = new NativeUsage(new PromptDetails(600), new CompletionDetails(120));
        Usage usage = new Usage() {
            @Override
            public Integer getPromptTokens() {
                return 1000;
            }

            @Override
            public Integer getCompletionTokens() {
                return 300;
            }

            @Override
            public Object getNativeUsage() {
                return nativeUsage;
            }
        };
        UsageMetrics metrics = UsageMetrics.of(usage);
        assertEquals(1000, metrics.inputTokens());
        assertEquals(300, metrics.outputTokens());
        assertEquals(600, metrics.cachedInputTokens());
        assertEquals(120, metrics.reasoningTokens());
    }

    @Test
    void missingNativeUsageIsZero() {
        Usage usage = new Usage() {
            @Override
            public Integer getPromptTokens() {
                return 10;
            }

            @Override
            public Integer getCompletionTokens() {
                return 5;
            }

            @Override
            public Object getNativeUsage() {
                return null;
            }
        };
        UsageMetrics metrics = UsageMetrics.of(usage);
        assertEquals(0, metrics.cachedInputTokens());
        assertEquals(0, metrics.cacheWriteTokens());
        assertEquals(0, metrics.reasoningTokens());
    }
}
