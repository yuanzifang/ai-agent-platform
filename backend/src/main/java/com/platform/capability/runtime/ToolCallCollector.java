package com.platform.capability.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 单次对话的工具调用采集器（线程安全）：工具在模型内部循环执行，完成后统一落 Span。
 */
public class ToolCallCollector {

    private final List<ToolCallEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final Consumer<ToolCallEvent> listener;

    public ToolCallCollector() {
        this(null);
    }

    public ToolCallCollector(Consumer<ToolCallEvent> listener) {
        this.listener = listener;
    }

    public void add(ToolCallEvent event) {
        events.add(event);
        if (listener != null) {
            try {
                listener.accept(event);
            } catch (Exception ignored) {
                // 事件推送失败不影响工具执行
            }
        }
    }

    public List<ToolCallEvent> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }
}
