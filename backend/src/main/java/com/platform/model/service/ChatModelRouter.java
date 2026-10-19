package com.platform.model.service;

import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.model.entity.ModelEntity;
import com.platform.model.entity.Provider;
import com.platform.model.mapper.ModelMapper;
import com.platform.model.mapper.ProviderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多 Provider 模型路由：按模型记录找到 Provider，用其 Base URL / 加密密钥构建 OpenAI 兼容 ChatModel。
 * 支持 OpenAI / DeepSeek / Ollama / DashScope 兼容模式 / 智谱 v4 / 火山 Ark / 自定义 OpenAI 兼容端点。
 * 原生 Anthropic/Gemini 协议暂未接入（可通过 OpenAI 兼容网关）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatModelRouter {

    private final ModelMapper modelMapper;
    private final ProviderMapper providerMapper;
    private final ProviderService providerService;
    private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();

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
        String cacheKey = provider.getId() + ":" + model.getModelName() + ":" + baseUrl + ":" + path
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
                .build();
        log.info("模型路由：provider={}({}) base={} path={} model={}",
                provider.getName(), provider.getProviderType(), baseUrl, path, model.getModelName());
        return chatModel;
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
