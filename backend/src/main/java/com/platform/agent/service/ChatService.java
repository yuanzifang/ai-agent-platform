package com.platform.agent.service;

import com.platform.billing.entity.BillingUsage;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.common.context.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 对话执行（架构 5.1）：ChatClient 不做全局单例，按请求构建；调试链路不写会话与正式 Trace，
 * Token 计入 billing_usage（source=debug，需求文档 3.1.3(10)）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatModel chatModel;          // 自动装配的 OpenAI 兼容实现（指向 DeepSeek）
    private final BillingUsageMapper billingMapper;

    public Map<String, Object> debugChat(Map<String, Object> config, String message) {
        String systemPrompt = config == null || config.get("systemPrompt") == null
                ? null : String.valueOf(config.get("systemPrompt"));
        Map<String, Object> modelConfig = config == null || config.get("modelConfig") == null
                ? Map.of() : asModelConfig(config.get("modelConfig"));

        String modelName = String.valueOf(modelConfig.getOrDefault("modelName", "deepseek-flash"));
        Double temperature = toDouble(modelConfig.get("temperature"));

        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(modelName);
        if (temperature != null) options.temperature(temperature);

        var spec = ChatClient.create(chatModel).prompt().options(options.build());
        if (systemPrompt != null && !systemPrompt.isBlank()) spec = spec.system(systemPrompt);

        long start = System.currentTimeMillis();
        var chatResponse = spec.user(message).call().chatResponse();
        String answer = chatResponse.getResult().getOutput().getText();
        Usage usage = chatResponse.getMetadata().getUsage();

        long inputTokens = usage == null ? 0 : usage.getPromptTokens();
        long outputTokens = usage == null ? 0 : usage.getCompletionTokens();

        recordUsage(null, "debug", modelName, inputTokens, outputTokens);
        log.info("debug chat: model={} in={} out={} latency={}ms",
                modelName, inputTokens, outputTokens, System.currentTimeMillis() - start);

        return Map.of("answer", answer == null ? "" : answer,
                "latencyMs", System.currentTimeMillis() - start,
                "usage", Map.of("inputTokens", inputTokens, "outputTokens", outputTokens));
    }

    private void recordUsage(Long agentId, String source, String modelName,
                             long inputTokens, long outputTokens) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            log.warn("无租户上下文，跳过用量记录（source={}）", source);
            return;
        }
        BillingUsage u = new BillingUsage();
        u.setTenantId(tenantId);
        u.setAgentId(agentId);
        u.setSource(source);
        u.setModelName(modelName);
        u.setInputTokens(inputTokens);
        u.setOutputTokens(outputTokens);
        u.setCost(null); // 计价权威 billing_rule，M5 接入前记 0/空
        u.setStatDate(LocalDate.now());
        u.setCreatedAt(LocalDateTime.now());
        billingMapper.insert(u);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asModelConfig(Object o) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return Map.of();
    }

    private Double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) { }
        }
        return null;
    }
}
