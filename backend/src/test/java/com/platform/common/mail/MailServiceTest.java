package com.platform.common.mail;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MailServiceTest {

    @Test
    void selectsEnglishTemplateByUserLocale() {
        JavaMailSender sender = mock(JavaMailSender.class);
        MailService mail = new MailService(sender);
        ReflectionTestUtils.setField(mail, "host", "smtp.example.com");
        ReflectionTestUtils.setField(mail, "from", "no-reply@example.com");

        assertTrue(mail.sendLocalized("user@example.com", "en-US",
                "中文主题", "中文正文", "English subject", "English body"));
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        assertEquals("English subject", captor.getValue().getSubject());
        assertEquals("English body", captor.getValue().getText());
    }

    @Test
    void keepsChineseForZhLocale() {
        JavaMailSender sender = mock(JavaMailSender.class);
        MailService mail = new MailService(sender);
        ReflectionTestUtils.setField(mail, "host", "smtp.example.com");

        assertTrue(mail.sendLocalized("user@example.com", "zh-CN",
                "中文主题", "中文正文", "English subject", "English body"));
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        assertEquals("中文主题", captor.getValue().getSubject());
    }
}
