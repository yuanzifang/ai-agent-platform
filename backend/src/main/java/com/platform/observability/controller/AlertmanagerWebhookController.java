package com.platform.observability.controller;

import com.platform.system.service.AlertWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 仅容器内网使用的 Alertmanager webhook 接收器：把 Alertmanager JSON 转成平台群机器人文本。
 * Nginx/Ingress 不反代 /internal/**；生产需确保后端端口不对公网暴露。
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class AlertmanagerWebhookController {

    private final AlertWebhookService alertWebhookService;

    @PostMapping("/alertmanager")
    public Map<String, Object> receive(@RequestBody Map<String, Object> payload) {
        List<?> alerts = payload.get("alerts") instanceof List<?> list ? list : List.of();
        int sent = 0;
        for (Object raw : alerts) {
            if (!(raw instanceof Map<?, ?> alert)) continue;
            Map<?, ?> labels = alert.get("labels") instanceof Map<?, ?> m ? m : Map.of();
            Map<?, ?> annotations = alert.get("annotations") instanceof Map<?, ?> m ? m : Map.of();
            String status = string(alert.get("status"), string(payload.get("status"), "firing"));
            String name = string(labels.get("alertname"), "unknown");
            StringBuilder detail = new StringBuilder("status=").append(status);
            append(detail, "severity", labels.get("severity"));
            append(detail, "instance", labels.get("instance"));
            append(detail, "summary", annotations.get("summary"));
            append(detail, "description", annotations.get("description"));
            if (alertWebhookService.alert("[" + status + "] " + name, detail.toString())) {
                sent++;
            }
        }
        return Map.of("alerts", alerts.size(), "sent", sent);
    }

    private static void append(StringBuilder target, String key, Object value) {
        String text = string(value, "");
        if (!text.isBlank()) target.append("\n").append(key).append("=").append(text);
    }

    private static String string(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }
}
