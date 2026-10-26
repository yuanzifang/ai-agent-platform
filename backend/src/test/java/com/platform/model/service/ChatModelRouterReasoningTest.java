package com.platform.model.service;

import com.platform.model.entity.ModelEntity;
import com.platform.model.entity.Provider;
import com.platform.model.mapper.ModelMapper;
import com.platform.model.mapper.ProviderMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 思考模式参数映射：模型能力决定是否注入，避免把不支持的参数发给 Provider。
 */
class ChatModelRouterReasoningTest {

    private ChatModelRouter router(String providerType, String capabilities, String defaultParams) {
        ModelMapper modelMapper = mock(ModelMapper.class);
        ProviderMapper providerMapper = mock(ProviderMapper.class);
        ProviderService providerService = mock(ProviderService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        ModelEntity model = new ModelEntity();
        model.setId(1L);
        model.setModelName("m1");
        model.setProviderId(9L);
        model.setCapabilities(capabilities);
        model.setDefaultParams(defaultParams);

        Provider provider = new Provider();
        provider.setId(9L);
        provider.setProviderType(providerType);

        when(modelMapper.findForPricing(eq("m1"), nullable(Long.class))).thenReturn(model);
        when(providerMapper.findByIdIgnore(9L)).thenReturn(provider);
        return new ChatModelRouter(modelMapper, providerMapper, providerService, redis);
    }

    @Test
    void openAiReasoningEffortWhenModelSupportsThinking() {
        ChatModelRouter router = router("OPENAI_COMPATIBLE",
                "{\"reasoning\":true,\"reasoningLevels\":[\"low\",\"medium\",\"high\"]}", "{}");

        OpenAiChatOptions options = (OpenAiChatOptions) router.options("m1", 0.7, null, true, "high");

        assertThat(options.getReasoningEffort()).isEqualTo("high");
        // 思考模式下温度不生效
        assertThat(options.getTemperature()).isNull();
    }

    @Test
    void keepsTemperatureWhenThinkingOff() {
        ChatModelRouter router = router("DEEPSEEK",
                "{\"reasoning\":true}", "{\"reasoningEffort\":\"medium\"}");

        OpenAiChatOptions options = (OpenAiChatOptions) router.options("m1", 0.3, null, false, "high");

        assertThat(options.getReasoningEffort()).isNull();
        assertThat(options.getTemperature()).isEqualTo(0.3);
    }

    @Test
    void ignoresThinkingWhenModelDoesNotDeclareReasoning() {
        ChatModelRouter router = router("DEEPSEEK", "{\"reasoning\":false}", "{}");

        OpenAiChatOptions options = (OpenAiChatOptions) router.options("m1", 0.2, null, true, "high");

        assertThat(options.getReasoningEffort()).isNull();
        assertThat(options.getTemperature()).isEqualTo(0.2);
    }

    @Test
    void anthropicUsesBudgetTokensAndRaisesMaxTokens() {
        ChatModelRouter router = router("ANTHROPIC",
                "{\"reasoning\":true,\"reasoningParam\":\"budget\"}", "{}");

        AnthropicChatOptions options = (AnthropicChatOptions) router.options("m1", 0.5, null, true, "high");

        assertThat(options.getThinking()).isNotNull();
        assertThat(options.getThinking().budgetTokens()).isEqualTo(8192);
        assertThat(options.getMaxTokens()).isGreaterThan(8192);
        assertThat(options.getTemperature()).isNull();
    }

    @Test
    void unsupportedLevelFallsBackToModelDefault() {
        ChatModelRouter router = router("OPENAI_COMPATIBLE",
                "{\"reasoning\":true,\"reasoningLevels\":[\"low\",\"medium\"]}", "{\"reasoningEffort\":\"low\"}");

        OpenAiChatOptions options = (OpenAiChatOptions) router.options("m1", 0.7, null, true, "high");

        assertThat(options.getReasoningEffort()).isEqualTo("low");
    }

    @Test
    void levelIsNormalized() {
        ChatModelRouter router = router("OPENAI_COMPATIBLE", "{\"reasoning\":true}", "{}");

        OpenAiChatOptions options = (OpenAiChatOptions) router.options("m1", 0.7, null, true, "MAX");

        assertThat(options.getReasoningEffort()).isEqualTo("high");
        assertThat(router.normalizeReasoningLevel("garbage")).isEqualTo("medium");
        assertThat(router.normalizeReasoningLevel(null)).isEqualTo("medium");
    }

    @Test
    void fallbackModelDefaultsToMediumWhenNoDefaultParams() {
        ChatModelRouter router = router("OPENAI_COMPATIBLE", "{\"reasoning\":true,\"reasoningParam\":\"effort\"}", null);

        OpenAiChatOptions options = (OpenAiChatOptions) router.options("m1", null, null, true, null);

        assertThat(options.getReasoningEffort()).isEqualTo("medium");
        assertThat(options.getTemperature()).isNull();
    }
}
