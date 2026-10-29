package com.platform.common.mail;

import com.platform.common.i18n.BackendMessages;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 邮件模板门面：模板 key 集中放在 i18n/messages*.properties，按收件人 locale 渲染。
 * 发送失败仍由 MailService 回退日志，不阻断业务。
 */
@Service
@RequiredArgsConstructor
public class MailTemplateService {

    private final MailService mailService;
    private final BackendMessages messages;

    public boolean send(String to, String locale, String key, Object... args) {
        Locale target = locale(locale);
        String subject = messages.get(key + ".subject", target, args);
        String body = messages.get(key + ".body", target, args);
        return mailService.sendLocalized(to, target.toLanguageTag(), subject, body, subject, body);
    }

    private Locale locale(String locale) {
        if (locale == null || locale.isBlank()) return Locale.SIMPLIFIED_CHINESE;
        return Locale.forLanguageTag(locale.replace('_', '-'));
    }
}
