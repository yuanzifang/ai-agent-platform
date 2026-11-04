package com.platform.model.client;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 模型协议适配 SPI。
 *
 * <p>当前实现基于 Spring AI 1.x；Spring Boot 4 / Spring AI 2 的实现可以替换本接口，
 * 核心路由不需要改 Provider 分支。</p>
 */
public interface ChatModelFactory {

    boolean supports(String providerType);

    ChatModel build(ChatModelSpec spec);

    ChatOptions options(ChatModelSpec spec, Double temperature, List<ToolCallback> callbacks,
                        boolean reasoningOn, String reasoningLevel,
                        boolean budgetStyle, Integer reasoningBudget);
}
