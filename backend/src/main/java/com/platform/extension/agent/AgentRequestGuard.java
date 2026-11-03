package com.platform.extension.agent;

import com.platform.agent.entity.AgentRegistry;

/**
 * Agent 级请求护栏扩展点。
 *
 * <p>核心对话链路只负责按顺序调用已注册的护栏，不包含任何行业规则。
 * 业务方通过 Agent 配置 {@code agentCapabilities.extensions} 显式启用实现，
 * 未启用的实现必须快速返回 {@code null}。</p>
 */
public interface AgentRequestGuard {

    /**
     * 在模型/RAG 调用前检查用户消息。
     *
     * @return 命中时返回非空结果；未启用或不命中返回 {@code null}
     */
    GuardResult check(AgentRegistry agent, String message);

    record GuardResult(boolean blocked, String answer, String rule) {
    }
}
