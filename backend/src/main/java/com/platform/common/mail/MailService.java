package com.platform.common.mail;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 邮件发送（需求 3.25，P1）：配置 SMTP 时真实发送；未配置或失败时回退日志（不阻断主流程）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.host:}")
    private String host;

    @Value("${app.mail.from:no-reply@ai-agent.local}")
    private String from;

    public boolean enabled() {
        return host != null && !host.isBlank();
    }

    public boolean send(String to, String subject, String text) {
        return sendLocalized(to, "zh-CN", subject, text, subject, text);
    }

    /** 按用户语言选择主题/正文；英文缺失时回退中文。 */
    public boolean sendLocalized(String to, String locale, String subjectZh, String textZh,
                                 String subjectEn, String textEn) {
        if (to == null || to.isBlank()) return false;
        boolean en = locale != null && locale.toLowerCase().startsWith("en");
        String subject = en && subjectEn != null && !subjectEn.isBlank() ? subjectEn : subjectZh;
        String text = en && textEn != null && !textEn.isBlank() ? textEn : textZh;
        if (!enabled()) {
            log.info("[MAIL-DISABLED] to={} subject={}", maskEmail(to), subject);
            return false;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            mailSender.send(message);
            log.info("邮件已发送 to={} subject={}", maskEmail(to), subject);
            return true;
        } catch (Exception e) {
            log.warn("邮件发送失败 to={} subject={}：{}", maskEmail(to), subject, e.getMessage());
            log.info("[MAIL-FALLBACK] to={} subject={}", maskEmail(to), subject);
            return false;
        }
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank()) return "-";
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        return email.substring(0, Math.min(2, at)) + "***" + email.substring(at);
    }
}
