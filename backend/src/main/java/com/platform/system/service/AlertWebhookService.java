package com.platform.system.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运维告警推送（生产闭环）：配额/异常/健康事件 → 钉钉/企微/飞书群机器人 webhook。
 * 单条文本消息，失败仅记录日志不阻断业务；DEMO/静默期可用 enabled=false 关闭。
 * 钉钉/飞书用 text 消息体，企微同构（markdown 类型三家 text 兼容）。
 */
@Slf4j
@Service
public class AlertWebhookService {

    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.create();
    private volatile boolean webhookDownLogged = false;

    @Value("${alert.webhook.enabled:false}")
    private boolean enabled;

    @Value("${alert.webhook.url:}")
    private String url;

    @Value("${alert.webhook.secret:}")
    private String secret;

    @Value("${alert.webhook.env:dev}")
    private String env;

    public AlertWebhookService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 统一入口：title 一句话摘要，detail 可空补充。 */
    public void alert(String title, String detail) {
        if (!enabled || url == null || url.isBlank()) return;
        try {
            String text = "【AI Agent 平台·" + env + "】" + title
                    + (detail == null || detail.isBlank() ? "" : "\n" + detail)
                    + "\n" + LocalDateTime.now();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("msgtype", "text");
            body.put("text", Map.of("content", text));
            if (secret != null && !secret.isBlank()) {
                // 钉钉加签（timestamp+secret HmacSHA256 → base64 → urlencoded）
                long ts = System.currentTimeMillis();
                String sign = dingtalkSign(ts, secret);
                body.put("timestamp", String.valueOf(ts));
                body.put("sign", sign);
            }
            String resp = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
            webhookDownLogged = false;
            if (resp != null && resp.contains("\"errcode\":0")) {
                log.info("告警已推送：{}", title);
            } else {
                log.warn("告警推送响应异常：{}", truncate(resp));
            }
        } catch (Exception e) {
            // webhook 故障不阻断业务；连续失败只打一次避免日志风暴
            if (!webhookDownLogged) {
                log.warn("告警推送失败（后续静默直至恢复）：{}", e.getMessage());
                webhookDownLogged = true;
            }
        }
    }

    private String dingtalkSign(long timestamp, String secret) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "HmacSHA256"));
        byte[] signData = mac.doFinal(stringToSign.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.net.URLEncoder.encode(java.util.Base64.getEncoder().encodeToString(signData),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
