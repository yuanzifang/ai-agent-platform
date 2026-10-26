package com.platform.observability.controller;

import com.platform.system.service.AlertWebhookService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertmanagerWebhookControllerTest {

    @Test
    void convertsAlertmanagerPayloadToText() {
        AlertWebhookService webhook = mock(AlertWebhookService.class);
        when(webhook.alert(anyString(), anyString())).thenReturn(true);
        AlertmanagerWebhookController controller = new AlertmanagerWebhookController(webhook);

        Map<String, Object> payload = Map.of(
                "status", "firing",
                "alerts", List.of(Map.of(
                        "status", "firing",
                        "labels", Map.of("alertname", "AiAgentBackendDown", "severity", "critical",
                                "instance", "backend:8080"),
                        "annotations", Map.of("summary", "后端不可用", "description", "连续 2 分钟抓取失败"))));

        Map<String, Object> result = controller.receive(payload);

        assertEquals(1, result.get("alerts"));
        assertEquals(1, result.get("sent"));
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(webhook).alert(title.capture(), detail.capture());
        assertEquals("[firing] AiAgentBackendDown", title.getValue());
        assertTrue(detail.getValue().contains("status=firing"));
        assertTrue(detail.getValue().contains("severity=critical"));
        assertTrue(detail.getValue().contains("instance=backend:8080"));
        assertTrue(detail.getValue().contains("summary=后端不可用"));
        assertTrue(detail.getValue().contains("description=连续 2 分钟抓取失败"));
    }

    @Test
    void reportsUnsentWhenWebhookDisabled() {
        AlertWebhookService webhook = mock(AlertWebhookService.class);
        AlertmanagerWebhookController controller = new AlertmanagerWebhookController(webhook);
        Map<String, Object> payload = Map.of("alerts", List.of(Map.of(
                "labels", Map.of("alertname", "X"))));

        Map<String, Object> result = controller.receive(payload);

        assertEquals(1, result.get("alerts"));
        assertEquals(0, result.get("sent"));
    }
}
