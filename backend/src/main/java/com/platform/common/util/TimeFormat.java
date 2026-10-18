package com.platform.common.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 查询参数时间解析：兼容 `yyyy-MM-dd`、`yyyy-MM-dd HH:mm`、`yyyy-MM-dd HH:mm:ss` 与 ISO `T` 分隔。 */
public final class TimeFormat {

    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TimeFormat() {
    }

    /** endOfDay=true 时，仅日期值取当日 23:59:59；否则取 00:00:00。 */
    public static LocalDateTime parseLenient(String text, boolean endOfDay) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim().replace('T', ' ');
        if (t.length() == 10) {
            return endOfDay ? LocalDate.parse(t).atTime(23, 59, 59) : LocalDate.parse(t).atStartOfDay();
        }
        if (t.length() == 16) t = t + ":00";
        return LocalDateTime.parse(t, FULL);
    }
}
