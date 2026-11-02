package com.platform.common.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeFormatTest {

    @Test
    void parsesIsoLocalDateTimeWithNanos() {
        LocalDateTime value = TimeFormat.parseLenient("2026-11-01T18:52:59.096061979", false);
        assertEquals(LocalDateTime.of(2026, 11, 1, 18, 52, 59, 96_061_979), value);
    }

    @Test
    void keepsLegacyDateAndSpaceFormats() {
        assertEquals(LocalDateTime.of(2026, 11, 1, 0, 0, 0),
                TimeFormat.parseLenient("2026-11-01", false));
        assertEquals(LocalDateTime.of(2026, 11, 1, 23, 59, 59),
                TimeFormat.parseLenient("2026-11-01", true));
        assertEquals(LocalDateTime.of(2026, 11, 1, 18, 52, 0),
                TimeFormat.parseLenient("2026-11-01 18:52", false));
    }
}
