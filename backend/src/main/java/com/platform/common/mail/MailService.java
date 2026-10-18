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
        if (to == null || to.isBlank()) return false;
        if (!enabled()) {
            log.info("[MAIL-DISABLED] to={} subject={} body={}", to, subject, text);
            return false;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            mailSender.send(message);
            log.info("邮件已发送 to={} subject={}", to, subject);
            return true;
        } catch (Exception e) {
            log.warn("邮件发送失败 to={} subject={}：{}", to, subject, e.getMessage());
            log.info("[MAIL-FALLBACK] to={} subject={} body={}", to, subject, text);
            return false;
        }
    }
}
