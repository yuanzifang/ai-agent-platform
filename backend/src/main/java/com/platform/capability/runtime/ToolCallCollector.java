package com.platform.capability.runtime;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 单次对话的工具调用采集器（线程安全）：工具在模型内部循环执行，完成后统一落 Span。
 * 同时承载“停止生成”的取消信号：SSE 流被 dispose 时置位，工具执行前/执行中轮询。
 */
@Slf4j
public class ToolCallCollector {

    /** 工具开始执行（用于前端实时步骤卡片）。 */
    public record ToolCallStart(String toolKey, String name, String type, String input, long startedAt) {
    }

    private final List<ToolCallEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final Consumer<ToolCallEvent> listener;
    private final Consumer<ToolCallStart> startListener;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Map<String, AtomicInteger> validationFailures = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> toolCallCounts = new ConcurrentHashMap<>();
    private final String traceId;
    private volatile Integer inputValidationMaxAttempts;

    public ToolCallCollector() {
        this(null, null, null);
    }

    public ToolCallCollector(Consumer<ToolCallEvent> listener) {
        this(listener, null, null);
    }

    public ToolCallCollector(Consumer<ToolCallEvent> listener, Consumer<ToolCallStart> startListener) {
        this(listener, startListener, null);
    }

    public ToolCallCollector(Consumer<ToolCallEvent> listener, Consumer<ToolCallStart> startListener,
                             String traceId) {
        this.listener = listener;
        this.startListener = startListener;
        this.traceId = traceId;
    }

    public void start(String toolKey, String name, String type, String input) {
        if (startListener == null) return;
        try {
            startListener.accept(new ToolCallStart(toolKey, name, type,
                    input == null ? "" : (input.length() > 300 ? input.substring(0, 300) : input),
                    System.currentTimeMillis()));
        } catch (Exception e) {
            log.debug("工具开始事件推送失败：{}", e.getMessage());
        }
    }

    public void add(ToolCallEvent event) {
        events.add(event);
        if (listener != null) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                // 事件推送失败不影响工具执行
                log.debug("工具事件推送失败：{}", e.getMessage());
            }
        }
    }

    /** 用户停止生成/连接断开：工具执行方轮询此信号。 */
    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** 同一轮对话内按工具累计入参校验失败次数，供模型纠正参数并设置重试上限。 */
    public int recordValidationFailure(String toolKey) {
        return validationFailures.computeIfAbsent(toolKey, k -> new AtomicInteger()).incrementAndGet();
    }

    public void setInputValidationMaxAttempts(Integer value) {
        if (value == null) return;
        this.inputValidationMaxAttempts = Math.max(1, Math.min(value, 20));
    }

    public int inputValidationMaxAttempts(int fallback) {
        return inputValidationMaxAttempts == null ? Math.max(1, fallback) : inputValidationMaxAttempts;
    }

    /** 同一次对话内按工具生成稳定执行键，供脚本/下游做幂等参考。 */
    public String nextToolCallKey(String toolKey) {
        int n = toolCallCounts.computeIfAbsent(toolKey, k -> new AtomicInteger()).incrementAndGet();
        String base = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
        return base + ":" + toolKey + ":" + n;
    }

    public List<ToolCallEvent> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }
}
