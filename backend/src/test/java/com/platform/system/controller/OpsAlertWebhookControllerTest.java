package com.platform.system.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.system.service.AlertWebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Alertmanager 接收端：令牌 fail-closed、载荷解析、归并推送。 */
class OpsAlertWebhookControllerTest {

    private OpsAlertWebhookController controller;
    private AlertWebhookService webhook;

    private static final String FIRING = """
            {"version":"4","status":"firing","receiver":"ops","alerts":[
              {"status":"firing",
               "labels":{"alertname":"AiAgentBackendDown","severity":"critical","instance":"backend:8080"},
               "annotations":{"summary":"后端不可达","description":"up==0 持续 2 分钟"},
               "startsAt":"2026-10-26T10:00:00Z","generatorURL":"http://prom/graph"},
              {"status":"firing",
               "labels":{"alertname":"AiAgentHighLatencyP95","severity":"warning"},
               "annotations":{"summary":"P95 延迟过高"},
               "startsAt":"2026-10-26T10:01:00Z"}
            ]}
            """;

    @BeforeEach
    void setUp() {
        webhook = mock(AlertWebhookService.class);
        controller = new OpsAlertWebhookController(webhook, new ObjectMapper());
        ReflectionTestUtils.setField(controller, "receiverToken", "sec-ret");
        ReflectionTestUtils.setField(controller, "envLabel", "prod");
    }

    @Test
    void tokenNotConfiguredFailsClosed() {
        ReflectionTestUtils.setField(controller, "receiverToken", "");
        var resp = controller.webhook("anything", FIRING);
        assertEquals(403, resp.code());
        verify(webhook, never()).alert(anyString(), anyString());
    }

    @Test
    void wrongTokenRejected() {
        var resp = controller.webhook("bad-token", FIRING);
        assertEquals(403, resp.code());
        verify(webhook, never()).alert(anyString(), anyString());
    }

    @Test
    void validTokenGroupsAndPushesFiringAlerts() {
        when(webhook.alert(anyString(), anyString())).thenReturn(true);
        var resp = controller.webhook("sec-ret", FIRING);
        assertEquals(0, resp.code());
        verify(webhook).alert(contains("2 条"), contains("🚨 [critical] • AiAgentBackendDown @ backend:8080：后端不可达"));
        Map<?, ?> data = (Map<?, ?>) resp.data();
        assertEquals(2, data.get("accepted"));
        assertEquals(true, data.get("delivered"));
    }

    @Test
    void resolvedAlertsMarkedAsRecovered() {
        String payload = """
                {"alerts":[{"status":"resolved",
                  "labels":{"alertname":"AiAgentBackendDown"},
                  "annotations":{"summary":"后端不可达"},"startsAt":"2026-10-26T10:00:00Z"}]}
                """;
        var resp = controller.webhook("sec-ret", payload);
        assertEquals(0, resp.code());
        verify(webhook).alert(contains("告警恢复"), contains("✅ 已恢复"));
    }

    @Test
    void malformedPayloadRejected() {
        var resp = controller.webhook("sec-ret", "not-json{");
        assertEquals(400, resp.code());
        verify(webhook, never()).alert(anyString(), anyString());
    }
}
