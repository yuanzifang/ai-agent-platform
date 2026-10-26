package com.platform.system.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.response.ApiResponse;
import com.platform.system.service.AlertWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Alertmanager 告警接收端（值班路由闭环的最后一环）：
 * Prometheus 规则 firing → Alertmanager 分组/静默 → 本端点 → AlertWebhookService → 群机器人。
 *
 * <p>安全：路径 permitAll（Alertmanager 无 JWT），由共享令牌 X-Alert-Token 校验；
 * 令牌未配置时一律 403（fail-closed），须显式设置 ALERT_RECEIVER_TOKEN 才启用接收。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ops/alerts")
public class OpsAlertWebhookController {

    private final AlertWebhookService alertWebhookService;
    private final ObjectMapper objectMapper;

    @Value("${alert.receiver.token:}")
    private String receiverToken;

    @Value("${alert.receiver.env:}")
    private String envLabel;

    @PostMapping("/webhook")
    public ApiResponse<Map<String, Object>> webhook(
            @RequestHeader(value = "X-Alert-Token", required = false) String token,
            @RequestBody String body) {
        if (receiverToken == null || receiverToken.isBlank()
                || token == null || !constantTimeEquals(token, receiverToken)) {
            log.warn("Alertmanager webhook 令牌校验失败（未配置令牌或不匹配）");
            return ApiResponse.error(403, "invalid alert token");
        }
        List<String> firing = new ArrayList<>();
        List<String> resolved = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(body);
            for (JsonNode alert : root.path("alerts")) {
                String name = alert.path("labels").path("alertname").asText("UNKNOWN");
                String severity = alert.path("labels").path("severity").asText("warning");
                String summary = alert.path("annotations").path("summary").asText("");
                String description = alert.path("annotations").path("description").asText("");
                String instance = alert.path("labels").path("instance").asText("");
                String status = alert.path("status").asText("firing");
                String startsAt = alert.path("startsAt").asText("");
                StringBuilder line = new StringBuilder();
                line.append("• ").append(name);
                if (!instance.isBlank()) line.append(" @ ").append(instance);
                if (!summary.isBlank()) line.append("：").append(summary);
                if (!description.isBlank() && !description.equals(summary)) line.append("（").append(description).append("）");
                if (!startsAt.isBlank()) {
                    try {
                        line.append(" 起于 ").append(OffsetDateTime.parse(startsAt)
                                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm")));
                    } catch (Exception ignored) {
                        // 非标准时间格式不影响主信息
                    }
                }
                if ("resolved".equals(status)) {
                    resolved.add("✅ 已恢复 " + line);
                } else {
                    firing.add("🚨 [" + severity + "] " + line);
                }
            }
        } catch (Exception e) {
            log.warn("Alertmanager webhook 载荷解析失败：{}", e.getMessage());
            return ApiResponse.error(400, "invalid payload");
        }
        if (firing.isEmpty() && resolved.isEmpty()) {
            return ApiResponse.ok(Map.of("accepted", 0));
        }
        String label = envLabel == null || envLabel.isBlank() ? "" : "【" + envLabel + "】";
        String title = label + (firing.isEmpty() ? "告警恢复 " : "告警 ") + (firing.size() + resolved.size()) + " 条";
        String detail = String.join("\n", firing);
        if (!resolved.isEmpty()) {
            detail = detail.isEmpty() ? String.join("\n", resolved) : detail + "\n" + String.join("\n", resolved);
        }
        boolean delivered = alertWebhookService.alert(title, detail);
        return ApiResponse.ok(Map.of("accepted", firing.size() + resolved.size(), "delivered", delivered));
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left.length() != right.length()) return false;
        int diff = 0;
        for (int i = 0; i < left.length(); i++) diff |= left.charAt(i) ^ right.charAt(i);
        return diff == 0;
    }
}
