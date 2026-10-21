package com.platform.observability.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceSamplingTest {

    @Test
    void fullRateKeepsEverything() {
        assertTrue(TraceService.shouldSample("abc", "SUCCESS", 1.0, true));
        assertTrue(TraceService.shouldSample("abc", "ERROR", 1.0, false));
    }

    @Test
    void zeroRateDropsSuccessButKeepsErrorsByDefault() {
        assertFalse(TraceService.shouldSample("abc", "SUCCESS", 0.0, true));
        assertTrue(TraceService.shouldSample("abc", "ERROR", 0.0, true));
        assertTrue(TraceService.shouldSample("abc", "BLOCKED", 0.0, true));
        assertFalse(TraceService.shouldSample("abc", "ERROR", 0.0, false));
    }

    @Test
    void deterministicAndBounded() {
        boolean first = TraceService.shouldSample("stable-trace-id", "SUCCESS", 0.5, true);
        for (int i = 0; i < 20; i++) {
            assertTrue(first == TraceService.shouldSample("stable-trace-id", "SUCCESS", 0.5, true));
        }
    }
}
