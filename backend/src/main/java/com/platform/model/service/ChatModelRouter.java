package com.platform.model.service;

import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.model.entity.ModelEntity;
import com.platform.model.entity.Provider;
import com.platform.model.mapper.ModelMapper;
import com.platform.model.mapper.ProviderMapper;
import lombok.RequiredArgsConstructor;
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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多 Provider 模型路由：按模型记录找到 Provider，用其 Base URL / 加密密钥构建 OpenAI 兼容 ChatModel。
 * 支持 OpenAI / DeepSeek / Ollama / DashScope 兼容模式 / 智谱 v4 / 火山 Ark / 自定义 OpenAI 兼容端点，
 * 以及 Anthropic 原生协议（/v1/messages，支持 tools / thinking 参数透传）。Gemini 原生暂未接入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatModelRouter {

    private final ModelMapper modelMapper;
    private final ProviderMapper providerMapper;
    private final ProviderService providerService;
    private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();
    private final org.springframework.data.redis.core.StringRedisTemplate redis;

    /** 轻量熔断（跨请求状态）：连续失败 ≥3（5 分钟窗口）→ 冷却 60s 内直接走备用，成功清零。 */
    public void recordModelFailure(String model) {
        if (model == null || model.isBlank()) return;
        try {
            String failKey = "model:fail:" + model;
            Long n = redis.opsForValue().increment(failKey);
            if (n != null && n == 1L) redis.expire(failKey, java.time.Duration.ofMinutes(5));
            if (n != null && n >= 3) redis.opsForValue().set("model:cooldown:" + model, "1", java.time.Duration.ofSeconds(60));
        } catch (Exception ignored) { }
    }

    public void recordModelSuccess(String model) {
        if (model == null || model.isBlank()) return;
        try {
            redis.delete("model:fail:" + model);
            redis.delete("model:cooldown:" + model);
        } catch (Exception ignored) { }
    }

    public boolean modelCooling(String model) {
        if (model == null || model.isBlank()) return false;
        try {
            return Boolean.TRUE.equals(redis.hasKey("model:cooldown:" + model));
        } catch (Exception e) {
            return false;
        }
    }

    public ChatModel resolve(String modelName) {
        return resolve(TenantContext.getTenantId(), modelName);
    }

    public ChatModel resolve(Long tenantId, String modelName) {
        ModelEntity model = null;
        if (modelName != null && !modelName.isBlank()) {
            model = modelMapper.findForPricing(modelName, tenantId);
        }
        if (model == null) model = modelMapper.findDefaultChat(tenantId);
        if (model == null) {
            throw new BusinessException(400, "未配置可用对话模型，请先在「模型接入」添加模型");
        }
        Provider provider = providerMapper.findByIdIgnore(model.getProviderId());
        if (provider == null || !"ENABLED".equals(provider.getStatus())) {
            throw new BusinessException(400, "模型「" + model.getModelName() + "」的 Provider 不可用");
        }
        String baseUrl = normalizeBase(provider.getBaseUrl());
        String path = completionsPath(provider);
        // 用户填写 https://api.xxx.com/v1 时避免与默认 /v1/chat/completions 叠加
        if (path.startsWith("/v1/") && baseUrl.endsWith("/v1")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 3);
        }
        String key = providerService.resolveApiKey(provider);
        // Provider 更新（地址/密钥等）后应重建连接，避免继续复用指向旧地址的长连接。
        String providerVersion = provider.getUpdatedAt() == null ? "0" : provider.getUpdatedAt().toString();
        String cacheKey = provider.getId() + ":" + providerVersion + ":" + model.getModelName()
                + ":" + baseUrl + ":" + path
                + ":" + Integer.toHexString((key == null ? "" : key).hashCode());
        String finalBase = baseUrl;
        String finalPath = path;
        ModelEntity finalModel = model;
        return cache.computeIfAbsent(cacheKey, k -> build(provider, finalModel, finalBase, finalPath, key));
    }

    public String defaultModelName() {
        ModelEntity m = modelMapper.findDefaultChat(TenantContext.getTenantId());
        return m == null ? "deepseek-flash" : m.getModelName();
    }

    public void evictAll() {
        cache.clear();
    }

    private ChatModel build(Provider provider, ModelEntity model, String baseUrl, String path, String key) {
        String type = provider.getProviderType() == null ? ""
                : provider.getProviderType().toUpperCase(Locale.ROOT);
        if ("ANTHROPIC".equals(type)) {
            AnthropicApi api = AnthropicApi.builder()
                    .baseUrl(baseUrl)
                    .completionsPath(anthropicPath(provider))
                    .apiKey(key == null || key.isBlank() ? "not-needed" : key)
                    .anthropicVersion("2023-06-01")
                    .build();
            AnthropicChatOptions options = AnthropicChatOptions.builder()
                    .model(model.getModelName())
                    .maxTokens(4096)
                    .build();
            AnthropicChatModel chatModel = AnthropicChatModel.builder()
                    .anthropicApi(api)
                    .defaultOptions(options)
                    .toolCallingManager(ToolCallingManager.builder().build())
                    .retryTemplate(fastRetry())
                    .build();
            log.info("模型路由：provider={}(ANTHROPIC) base={} path={} model={}",
                    provider.getName(), baseUrl, anthropicPath(provider), model.getModelName());
            return chatModel;
        }
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .completionsPath(path)
                .apiKey(key == null || key.isBlank() ? "not-needed" : key)
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder().model(model.getModelName()).build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .toolCallingManager(ToolCallingManager.builder().build())
                .retryTemplate(fastRetry())
                .build();
        log.info("模型路由：provider={}({}) base={} path={} model={}",
                provider.getName(), provider.getProviderType(), baseUrl, path, model.getModelName());
        return chatModel;
    }

    /** 快速失败：最多重试 1 次，避免 5xx 长时间重试阻塞模型降级。 */
    private org.springframework.retry.support.RetryTemplate fastRetry() {
        return org.springframework.retry.support.RetryTemplate.builder()
                .maxAttempts(2)
                .fixedBackoff(200)
                .retryOn(org.springframework.ai.retry.TransientAiException.class)
                .build();
    }

    /** 按模型/Provider 生成对应协议的 ChatOptions（工具回调统一注入）。 */
    public ChatOptions options(String modelName, Double temperature, List<ToolCallback> callbacks) {
        return options(modelName, temperature, callbacks, null, null);
    }

    /**
     * 同上，额外支持思考模式：
     * - Anthropic 原生协议：thinking + budget_tokens（low/medium/high 对应 2k/4k/8k，且 max_tokens 必须大于 budget）
     * - OpenAI 兼容协议：reasoning_effort（minimal/low/medium/high）
     *
     * 是否真正生效由「模型能力」决定：model.capabilities.reasoning=true 才注入，
     * 参数风格取 capabilities.reasoningParam（effort/budget，缺省按 Provider 类型推断），
     * 思考模式下不再传 temperature（推理模型普遍不支持自定义温度，避免 400）。
     */
    public ChatOptions options(String modelName, Double temperature, List<ToolCallback> callbacks,
                               Boolean thinking, String reasoningEffort) {
        ModelEntity model = modelName == null || modelName.isBlank()
                ? modelMapper.findDefaultChat(TenantContext.getTenantId())
                : modelMapper.findForPricing(modelName, TenantContext.getTenantId());
        if (model == null) model = modelMapper.findDefaultChat(TenantContext.getTenantId());
        if (model == null) {
            throw new BusinessException(400, "未配置可用对话模型，请先在「模型接入」添加模型");
        }
        Provider provider = providerMapper.findByIdIgnore(model.getProviderId());
        String type = provider == null || provider.getProviderType() == null ? ""
                : provider.getProviderType().toUpperCase(Locale.ROOT);
        String effectiveModel = modelName == null || modelName.isBlank()
                ? model.getModelName() : modelName;

        Map<String, Object> caps = json(model.getCapabilities());
        Map<String, Object> defaults = json(model.getDefaultParams());
        boolean reasoningOn = Boolean.TRUE.equals(thinking) && Boolean.TRUE.equals(caps.get("reasoning"));
        String level = normalizeReasoningLevel(reasoningEffort != null
                ? reasoningEffort
                : str(defaults.get("reasoningEffort")));
        // 模型声明了可选级别时，不支持的级别回落到默认/medium
        if (reasoningOn && caps.get("reasoningLevels") instanceof List<?> levels && !levels.isEmpty()) {
            List<String> allowed = levels.stream().map(String::valueOf).map(this::normalizeReasoningLevel).toList();
            if (!allowed.contains(level)) {
                String fallbackLevel = normalizeReasoningLevel(str(defaults.get("reasoningEffort")));
                level = allowed.contains(fallbackLevel) ? fallbackLevel : allowed.get(0);
            }
        }
        String style = str(caps.get("reasoningParam"));
        boolean budgetStyle = "budget".equalsIgnoreCase(style) || ("ANTHROPIC".equals(type) && !"effort".equalsIgnoreCase(style));

        if ("ANTHROPIC".equals(type)) {
            AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder().model(effectiveModel);
            if (reasoningOn && budgetStyle) {
                int budget = reasoningBudget(level);
                builder.maxTokens(budget + 4096)
                        .thinking(org.springframework.ai.anthropic.api.AnthropicApi.ThinkingType.ENABLED, budget);
            } else {
                builder.maxTokens(4096);
                if (temperature != null) builder.temperature(temperature);
            }
            if (callbacks != null && !callbacks.isEmpty()) builder.toolCallbacks(callbacks);
            return builder.build();
        }
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model(effectiveModel);
        if (reasoningOn) {
            builder.reasoningEffort(level);
        } else if (temperature != null) {
            builder.temperature(temperature);
        }
        if (callbacks != null && !callbacks.isEmpty()) builder.toolCallbacks(callbacks);
        return builder.build();
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            return JSON.readValue(raw, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** 统一思考级别：minimal / low / medium / high，非法值按 medium。 */
    public String normalizeReasoningLevel(String level) {
        String v = level == null ? "" : level.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "minimal", "low", "medium", "high" -> v;
            case "max", "xhigh" -> "high";
            case "mid", "normal" -> "medium";
            default -> "medium";
        };
    }

    /** Anthropic budget_tokens（必须小于 max_tokens）。 */
    private int reasoningBudget(String level) {
        return switch (normalizeReasoningLevel(level)) {
            case "minimal" -> 1024;
            case "low" -> 2048;
            case "high" -> 8192;
            default -> 4096;
        };
    }

    /** 402/429/5xx/连接超时视为可降级；4xx 参数错误不降级。 */
    public boolean isFallbackable(Throwable error) {
        Throwable t = error;
        while (t != null) {
            String msg = t.getMessage() == null ? "" : t.getMessage();
            String lower = msg.toLowerCase(Locale.ROOT);
            if (lower.contains("402") || lower.contains("429") || lower.contains("500")
                    || lower.contains("502") || lower.contains("503") || lower.contains("504")
                    || lower.contains("timeout") || lower.contains("timed out")
                    || lower.contains("connection refused") || lower.contains("connect")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private String anthropicPath(Provider provider) {
        String type = provider.getProviderType() == null ? ""
                : provider.getProviderType().toUpperCase(Locale.ROOT);
        // OpenCode Go 等网关可能把 Anthropic 挂在 /anthropic 下，Base URL 由用户填写
        return "/v1/messages";
    }

    private String normalizeBase(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BusinessException(400, "Provider Base URL 不能为空");
        }
        return baseUrl.trim().replaceAll("/+$", "");
    }

    private String completionsPath(Provider provider) {
        String type = provider.getProviderType() == null ? ""
                : provider.getProviderType().toUpperCase(Locale.ROOT);
        return switch (type) {
            // 智谱 base 形如 https://open.bigmodel.cn/api/paas/v4
            case "ZHIPU", "GLM" -> "/chat/completions";
            // 火山 Ark base 形如 https://ark.cn-beijing.volces.com/api/v3
            case "VOLC" -> "/chat/completions";
            default -> "/v1/chat/completions";
        };
    }
}
