package com.platform.capability.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolCallCollectorTest {

    @Test
    void countsValidationFailuresPerTool() {
        ToolCallCollector collector = new ToolCallCollector();

        assertEquals(1, collector.recordValidationFailure("tool-a"));
        assertEquals(2, collector.recordValidationFailure("tool-a"));
        assertEquals(1, collector.recordValidationFailure("tool-b"));
        assertEquals(3, collector.inputValidationMaxAttempts(3));
        collector.setInputValidationMaxAttempts(5);
        assertEquals(5, collector.inputValidationMaxAttempts(3));
        ToolCallCollector traced = new ToolCallCollector(null, null, "trace-1");
        assertEquals("trace-1:skill:1", traced.nextToolCallKey("skill"));
        assertEquals("trace-1:skill:2", traced.nextToolCallKey("skill"));
    }
}
