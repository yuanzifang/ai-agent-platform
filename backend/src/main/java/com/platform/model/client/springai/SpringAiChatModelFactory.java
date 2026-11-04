package com.platform.model.client.springai;

import com.platform.model.client.ChatModelFactory;
import com.platform.model.client.ChatModelSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Spring AI 1.x 的协议适配实现。
 *
 * <p>所有 Spring AI Provider API 都集中在这里；ChatModelRouter 只依赖 SPI。</p>
 */
@Slf4j
@Component
public class SpringAiChatModelFactory implements ChatModelFactory {

    @Override
    public boolean supports(String providerType) {
        return true;
    }

    @Override
    public ChatModel build(ChatModelSpec spec) {
        String type = providerType(spec);
        String key = spec.apiKey() == null || spec.apiKey().isBlank() ? "not-needed" : spec.apiKey();
        if ("ANTHROPIC".equals(type)) {
            AnthropicApi api = AnthropicApi.builder()
                    .baseUrl(spec.baseUrl())
                    .completionsPath(spec.path())
                    .apiKey(key)
                    .anthropicVersion("2023-06-01")
                    .build();
            AnthropicChatOptions options = AnthropicChatOptions.builder()
                    .model(spec.modelName())
                    .maxTokens(spec.maxTokens())
                    .build();
            AnthropicChatModel chatModel = AnthropicChatModel.builder()
                    .anthropicApi(api)
                    .defaultOptions(options)
                    .toolCallingManager(ToolCallingManager.builder().build())
                    .retryTemplate(fastRetry())
                    .build();
            log.info("模型路由：provider={}(ANTHROPIC) base={} path={} model={}",
                    spec.providerName(), spec.baseUrl(), spec.path(), spec.modelName());
            return chatModel;
        }
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(spec.baseUrl())
                .completionsPath(spec.path())
                .apiKey(key)
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder().model(spec.modelName()).build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .toolCallingManager(ToolCallingManager.builder().build())
                .retryTemplate(fastRetry())
                .build();
        log.info("模型路由：provider={}({}) base={} path={} model={}",
                spec.providerName(), spec.providerType(), spec.baseUrl(), spec.path(), spec.modelName());
        return chatModel;
    }

    @Override
    public ChatOptions options(ChatModelSpec spec, Double temperature, List<ToolCallback> callbacks,
                               boolean reasoningOn, String reasoningLevel,
                               boolean budgetStyle, Integer reasoningBudget) {
        String type = providerType(spec);
        if ("ANTHROPIC".equals(type)) {
            AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder().model(spec.modelName());
            if (reasoningOn && budgetStyle) {
                int budget = reasoningBudget == null ? 4096 : reasoningBudget;
                builder.maxTokens(budget + spec.maxTokens())
                        .thinking(AnthropicApi.ThinkingType.ENABLED, budget);
            } else {
                builder.maxTokens(spec.maxTokens());
                if (temperature != null) builder.temperature(temperature);
            }
            if (callbacks != null && !callbacks.isEmpty()) builder.toolCallbacks(callbacks);
            return builder.build();
        }
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model(spec.modelName());
        if (reasoningOn) {
            builder.reasoningEffort(reasoningLevel);
        } else if (temperature != null) {
            builder.temperature(temperature);
        }
        if (callbacks != null && !callbacks.isEmpty()) builder.toolCallbacks(callbacks);
        return builder.build();
    }

    private String providerType(ChatModelSpec spec) {
        return spec.providerType() == null ? "" : spec.providerType().toUpperCase(Locale.ROOT);
    }

    /** 快速失败：最多重试 1 次，避免 5xx 长时间重试阻塞模型降级。 */
    private org.springframework.retry.support.RetryTemplate fastRetry() {
        return org.springframework.retry.support.RetryTemplate.builder()
                .maxAttempts(2)
                .fixedBackoff(200)
                .retryOn(TransientAiException.class)
                .build();
    }
}
