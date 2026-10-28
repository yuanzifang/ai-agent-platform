package com.platform.system.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 群机器人 Webhook 通道：钉钉 / 企业微信 / 飞书（按 URL 自动识别，type 可强制指定）。
 *
 * <p>三家报文不同——钉钉/企微同为 {@code {msgtype:"text",text:{content}}}，
 * <b>飞书是 {@code {msg_type:"text",content:{text}}} 且字段名为下划线</b>（此前统一用
 * 钉钉报文发飞书会被飞书拒绝，本类修正）。飞书开启加签时：
 * sign = Base64(HmacSHA256(key = timestamp+"\n"+secret, data = ""))，与钉钉（key=secret、
 * data=timestamp+"\n"+secret，放 query）互为镜像，不可混用。</p>
 */
@Slf4j
@Component
public class WebhookAlertSender implements AlertSender {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final org.springframework.web.client.RestClient restClient =
            org.springframework.web.client.RestClient.create();

    @Value("${alert.webhook.enabled:false}")
    private boolean enabled;

    @Value("${alert.webhook.url:}")
    private String url;

    @Value("${alert.webhook.secret:}")
    private String secret;

    /** auto=按 URL 识别；可显式指定 dingtalk/wecom/feishu（自建代理地址无法从 URL 判断时用）。 */
    @Value("${alert.webhook.type:auto}")
    private String type;

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public boolean enabled() {
        return enabled && url != null && !url.isBlank();
    }

    @Override
    public boolean send(String title, String text) {
        try {
            WebhookKind kind = detectKind();
            Map<String, Object> body = switch (kind) {
                case FEISHU -> feishuBody(text);
                case DINGTALK -> dingtalkBody(text);
                case WECOM -> wecomBody(text);
            };
            String target = kind == WebhookKind.DINGTALK && secret != null && !secret.isBlank()
                    ? appendQuery(url, "timestamp", String.valueOf(System.currentTimeMillis()),
                            "sign", dingtalkSign(System.currentTimeMillis(), secret))
                    : url;
            String resp = restClient.post()
                    .uri(target)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
            boolean ok = responseOk(resp, kind);
            if (!ok) log.warn("[webhook:{}] 告警推送响应异常：{}", kind, truncate(resp));
            return ok;
        } catch (Exception e) {
            log.warn("[webhook] 告警推送失败：{}", e.getMessage());
            return false;
        }
    }

    enum WebhookKind { DINGTALK, WECOM, FEISHU }

    WebhookKind detectKind() {
        if (!"auto".equalsIgnoreCase(type)) {
            return WebhookKind.valueOf(type.trim().toUpperCase(Locale.ROOT));
        }
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (u.contains("dingtalk")) return WebhookKind.DINGTALK;
        if (u.contains("open.feishu.cn") || u.contains("open.larksuite.com") || u.contains("feishu")) {
            return WebhookKind.FEISHU;
        }
        if (u.contains("qyapi.weixin.qq.com") || u.contains("weixin") || u.contains("wecom")) {
            return WebhookKind.WECOM;
        }
        return WebhookKind.DINGTALK;
    }

    /** 钉钉/企微共用 text 结构（测试断言用，包级可见）。 */
    Map<String, Object> dingtalkBody(String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msgtype", "text");
        body.put("text", Map.of("content", text));
        return body;
    }

    Map<String, Object> wecomBody(String text) {
        return dingtalkBody(text);
    }

    /** 飞书：msg_type（下划线）+ content.text；加签时附 timestamp/sign（HMAC 镜像算法）。 */
    Map<String, Object> feishuBody(String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msg_type", "text");
        body.put("content", Map.of("text", text));
        if (secret != null && !secret.isBlank()) {
            long ts = System.currentTimeMillis() / 1000;
            body.put("timestamp", String.valueOf(ts));
            body.put("sign", feishuSign(ts, secret));
        }
        return body;
    }

    /** 飞书加签：key = timestamp+"\n"+secret，data 为空串（与钉钉互为镜像，勿混）。 */
    static String feishuSign(long timestamp, String secret) {
        try {
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
        } catch (Exception e) {
            throw new IllegalStateException("飞书签名失败", e);
        }
    }

    private String dingtalkSign(long timestamp, String secret) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        return URLEncoder.encode(Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);
    }

    private String appendQuery(String base, String k1, String v1, String k2, String v2) {
        char sep = base.contains("?") ? '&' : '?';
        return base + sep + k1 + "=" + v1 + "&" + k2 + "=" + v2;
    }

    private boolean responseOk(String resp, WebhookKind kind) {
        if (resp == null || resp.isBlank()) return true;
        try {
            var node = objectMapper.readTree(resp);
            // 飞书错误码字段是 code（0 成功）且可能有 extra；钉钉 errcode；企微 errcode
            if (kind == WebhookKind.FEISHU && node.has("code")) return node.path("code").asInt(-1) == 0;
            if (node.has("errcode")) return node.path("errcode").asInt(-1) == 0;
            if (node.has("code")) return node.path("code").asInt(-1) == 0;
            return true;
        } catch (Exception ignored) {
            return true;
        }
    }

    private String truncate(String s) {
        return s == null ? "" : s.substring(0, Math.min(s.length(), 200));
    }
}
