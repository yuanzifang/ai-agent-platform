package com.platform.capability.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 单次请求的步骤采集器（线程安全）。 */
public class TraceStepCollector {

    private final List<TraceStep> steps = Collections.synchronizedList(new ArrayList<>());

    public void add(String spanType, String name, String status, long startMs, long endMs,
                    Map<String, Object> attributes) {
        steps.add(new TraceStep(spanType, name, status, startMs, endMs,
                attributes == null ? Map.of() : attributes));
    }

    /** 执行并记录耗时的便捷方法。 */
    public <T> T measure(String spanType, String name, Map<String, Object> attributes, Supplier<T> action) {
        long start = System.currentTimeMillis();
        String status = "SUCCESS";
        try {
            return action.get();
        } catch (RuntimeException e) {
            status = "ERROR";
            throw e;
        } finally {
            add(spanType, name, status, start, System.currentTimeMillis(), attributes);
        }
    }

    public void measure(String spanType, String name, Map<String, Object> attributes, Runnable action) {
        measure(spanType, name, attributes, () -> {
            action.run();
            return null;
        });
    }

    public List<TraceStep> steps() {
        synchronized (steps) {
            return List.copyOf(steps);
        }
    }
}
