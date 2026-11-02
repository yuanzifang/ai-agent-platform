package com.platform.common.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** 查询参数时间解析：兼容 `yyyy-MM-dd`、`yyyy-MM-dd HH:mm`、`yyyy-MM-dd HH:mm:ss` 与 ISO `T` 分隔。 */
public final class TimeFormat {

    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TimeFormat() {
    }

    /** endOfDay=true 时，仅日期值取当日 23:59:59；否则取 00:00:00。 */
    public static LocalDateTime parseLenient(String text, boolean endOfDay) {
        if (text == null || text.isBlank()) return null;
        String raw = text.trim();
        if (raw.length() == 10) {
            return endOfDay ? LocalDate.parse(raw).atTime(23, 59, 59) : LocalDate.parse(raw).atStartOfDay();
        }
        // API 返回的 LocalDateTime 可能是 ISO T + 纳秒；先走 ISO 解析，避免 500。
        try {
            return LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            // 继续兼容空格分隔、无秒、带时区后缀的旧参数。
        }
        String t = raw.replace('T', ' ');
        if (t.length() == 16) t = t + ":00";
        if (t.length() > 19) t = t.substring(0, 19);
        return LocalDateTime.parse(t, FULL);
    }
}
