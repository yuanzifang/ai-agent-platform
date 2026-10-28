package com.platform.system.service;

import com.platform.system.alert.AliyunSmsAlertSender;
import com.platform.system.alert.EmailAlertSender;
import com.platform.system.alert.WebhookAlertSender;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 多通道门面：每个已启用通道都尝试，单通道失败不阻断其他通道。 */
class AlertWebhookServiceFanoutTest {

    @Test
    void fansOutAndIsolatesChannelFailures() {
        WebhookAlertSender webhook = mock(WebhookAlertSender.class);
        EmailAlertSender email = mock(EmailAlertSender.class);
        AliyunSmsAlertSender sms = mock(AliyunSmsAlertSender.class);
        when(webhook.name()).thenReturn("webhook");
        when(webhook.enabled()).thenReturn(true);
        when(webhook.send(anyString(), anyString())).thenReturn(true);
        when(email.name()).thenReturn("email");
        when(email.enabled()).thenReturn(true);
        when(email.send(anyString(), anyString())).thenReturn(false);
        when(sms.name()).thenReturn("aliyun-sms");
        when(sms.enabled()).thenReturn(true);
        when(sms.send(anyString(), anyString())).thenReturn(true);

        AlertWebhookService service = new AlertWebhookService(webhook, email, sms);
        Map<String, Boolean> results = service.alertAll("测试", "详情");

        assertEquals(3, results.size());
        assertTrue(results.get("webhook"));
        assertFalse(results.get("email"));
        assertTrue(results.get("aliyun-sms"));
        assertTrue(service.alert("再测", ""));
        verify(webhook, org.mockito.Mockito.times(2)).send(anyString(), anyString());
        verify(email, org.mockito.Mockito.times(2)).send(anyString(), anyString());
        verify(sms, org.mockito.Mockito.times(2)).send(anyString(), anyString());
    }

    @Test
    void disabledChannelsAreNotInvoked() {
        WebhookAlertSender webhook = mock(WebhookAlertSender.class);
        EmailAlertSender email = mock(EmailAlertSender.class);
        AliyunSmsAlertSender sms = mock(AliyunSmsAlertSender.class);
        when(webhook.name()).thenReturn("webhook");
        when(webhook.enabled()).thenReturn(false);
        when(email.name()).thenReturn("email");
        when(email.enabled()).thenReturn(false);
        when(sms.name()).thenReturn("aliyun-sms");
        when(sms.enabled()).thenReturn(false);

        AlertWebhookService service = new AlertWebhookService(webhook, email, sms);
        assertTrue(service.alertAll("测试", "").isEmpty());
        assertFalse(service.isConfigured());
        verify(webhook, org.mockito.Mockito.never()).send(anyString(), anyString());
    }
}
