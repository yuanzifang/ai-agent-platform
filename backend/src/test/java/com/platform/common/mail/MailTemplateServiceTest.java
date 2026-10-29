package com.platform.common.mail;

import com.platform.common.i18n.BackendMessages;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MailTemplateServiceTest {

    @Test
    void rendersEnglishTemplateFromBundle() {
        JavaMailSender sender = mock(JavaMailSender.class);
        MailService mail = new MailService(sender);
        ReflectionTestUtils.setField(mail, "host", "smtp.example.com");
        ReflectionTestUtils.setField(mail, "from", "no-reply@example.com");
        MailTemplateService templates = new MailTemplateService(mail, messages());

        assertTrue(templates.send("user@example.com", "en-US", "mail.reset", "https://example.com/reset"));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("AI Agent Platform - Reset password",
                captor.getValue().getSubject());
        org.junit.jupiter.api.Assertions.assertTrue(
                captor.getValue().getText().contains("https://example.com/reset"));
    }

    private BackendMessages messages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return new BackendMessages(source);
    }
}
