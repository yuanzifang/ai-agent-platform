package com.platform.capability.runtime;

import java.util.Map;

/**
 * 对话执行步骤（检索/装配/记忆/持久化等），最终作为 Span 挂到 Trace 根节点下。
 */
public record TraceStep(String spanType, String name, String status,
                        long startEpochMs, long endEpochMs, Map<String, Object> attributes) {

    public long durationMs() {
        return Math.max(endEpochMs - startEpochMs, 0);
    }
}
