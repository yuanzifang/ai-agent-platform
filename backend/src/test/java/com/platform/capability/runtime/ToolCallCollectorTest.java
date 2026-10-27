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
    }
}
