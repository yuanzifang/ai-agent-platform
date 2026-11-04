package com.platform.model.client;

/**
 * Provider 无关的模型客户端构建参数。
 *
 * <p>核心模型路由只负责解析 Provider/模型和缓存，不再直接依赖 Spring AI 的
 * OpenAI/Anthropic API 类型。升级 Spring AI 时只需替换 {@link ChatModelFactory} 实现。</p>
 */
public record ChatModelSpec(
        String providerName,
        String providerType,
        String modelName,
        String baseUrl,
        String path,
        String apiKey,
        int maxTokens
) {
}
