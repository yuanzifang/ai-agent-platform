package com.platform.system.alert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 邮件告警通道：spring-boot-starter-mail（JavaMailSender 由 spring.mail.host 配置驱动）。
 * 收件人可多个（逗号分隔）。未启用或邮件基础设施缺失时静默跳过。
 */
@Slf4j
@Component
public class EmailAlertSender implements AlertSender {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${alert.email.enabled:false}")
    private boolean enabled;

    @Value("${alert.email.from:${alert.email.username:}}")
    private String from;

    @Value("${alert.email.to:}")
    private String to;

    public EmailAlertSender(ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.mailSenderProvider = mailSenderProvider;
    }

    @Override
    public String name() {
        return "email";
    }

    @Override
    public boolean enabled() {
        return enabled && mailSenderProvider.getIfAvailable() != null
                && to != null && !to.isBlank();
    }

    @Override
    public boolean send(String title, String text) {
        try {
            JavaMailSender sender = mailSenderProvider.getObject();
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(Arrays.stream(to.split("[,;]"))
                    .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new));
            message.setSubject(title);
            message.setText(text == null ? "" : text);
            sender.send(message);
            return true;
        } catch (Exception e) {
            log.warn("[email] 告警邮件发送失败：{}", e.getMessage());
            return false;
        }
    }
}
