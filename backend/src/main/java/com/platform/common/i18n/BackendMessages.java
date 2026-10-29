package com.platform.common.i18n;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.util.Locale;

/** 统一的后端消息解析入口；未知 key 回退到调用方提供的默认文案。 */
@Service
@RequiredArgsConstructor
public class BackendMessages {

    private final MessageSource messageSource;

    public String get(String key, Object... args) {
        return get(key, LocaleContextHolder.getLocale(), args);
    }

    public String get(String key, Locale locale, Object... args) {
        Locale target = locale == null ? Locale.SIMPLIFIED_CHINESE : locale;
        return messageSource.getMessage(key, args, key, target);
    }

    public String getOrDefault(String key, String fallback, Locale locale, Object... args) {
        Locale target = locale == null ? Locale.SIMPLIFIED_CHINESE : locale;
        return messageSource.getMessage(key, args, fallback, target);
    }
}
