package com.platform.system.alert;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Webhook 通道：三家报文差异与签名算法（钉钉/飞书互为镜像，不可混用——历史 bug 防回归）。 */
class WebhookAlertSenderTest {

    private final WebhookAlertSender sender = new WebhookAlertSender();

    @Test
    void dingTalkBodyShape() {
        Map<String, Object> body = sender.dingtalkBody("内容");
        assertEquals("text", body.get("msgtype"));
        assertEquals(Map.of("content", "内容"), body.get("text"));
        assertTrue(sender.wecomBody("x").equals(sender.dingtalkBody("x")), "企微与钉钉同构");
    }

    @Test
    void feishuBodyUsesUnderscoreFields() {
        Map<String, Object> body = sender.feishuBody("告警内容");
        assertEquals("text", body.get("msg_type"), "飞书是 msg_type 不是 msgtype");
        assertEquals(Map.of("text", "告警内容"), body.get("content"));
    }

    @Test
    void feishuBodyWithoutSecretHasNoSignFields() {
        Map<String, Object> body = sender.feishuBody("x");
        assertTrue(!body.containsKey("timestamp"), "未配加签时不得带 timestamp");
        assertTrue(!body.containsKey("sign"), "未配加签时不得带 sign");
    }

    @Test
    void feishuSignAlgorithmIsMirrorOfDingTalk() throws Exception {
        long ts = 1729987200L;
        String secret = "my-secret";
        String sign = WebhookAlertSender.feishuSign(ts, secret);
        // 独立实现校验：key = timestamp+"\n"+secret，data 为空
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec((ts + "\n" + secret).getBytes("UTF-8"), "HmacSHA256"));
        String expected = Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
        assertEquals(expected, sign);
    }
}
