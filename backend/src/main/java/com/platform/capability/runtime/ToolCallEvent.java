package com.platform.capability.runtime;

/**
 * 一次工具调用事件（落 Trace Span + SSE 事件）。
 * subAgentId 非空表示该工具是子 Agent 调用（需求 3.1.3(8)）。
 */
public record ToolCallEvent(String toolName, String displayName, String toolType,
                            Long subAgentId, String subAgentName,
                            String status, long durationMs,
                            String input, String output,
                            long inputTokens, long outputTokens,
                            long startEpochMs, long endEpochMs) {
}
