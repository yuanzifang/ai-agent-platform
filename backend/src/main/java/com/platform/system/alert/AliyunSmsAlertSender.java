package com.platform.system.alert;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.teaopenapi.models.Config;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 阿里云短信告警通道（dysmsapi v4 SDK）。
 * 前置：阿里云控制台开通短信服务、申请签名（signName）与模板（templateCode，
 * 模板需含 ${content} 变量）。多个手机号逗号分隔。客户端懒初始化：未配置凭证不建连。
 */
@Slf4j
@Component
public class AliyunSmsAlertSender implements AlertSender {

    @Value("${alert.sms.enabled:false}")
    private boolean enabled;

    @Value("${alert.sms.access-key-id:}")
    private String accessKeyId;

    @Value("${alert.sms.access-key-secret:}")
    private String accessKeySecret;

    @Value("${alert.sms.endpoint:dysmsapi.aliyuncs.com}")
    private String endpoint;

    @Value("${alert.sms.sign-name:}")
    private String signName;

    @Value("${alert.sms.template-code:}")
    private String templateCode;

    @Value("${alert.sms.phone-numbers:}")
    private String phoneNumbers;

    private volatile Client client;

    /** 客户端懒初始化：未配置凭证时不建立连接。 */
    private synchronized Client client() throws Exception {
        if (client == null) {
            client = new Client(new Config()
                    .setAccessKeyId(accessKeyId)
                    .setAccessKeySecret(accessKeySecret)
                    .setEndpoint(endpoint));
        }
        return client;
    }

    @Override
    public String name() {
        return "aliyun-sms";
    }

    @Override
    public boolean enabled() {
        return enabled && !accessKeyId.isBlank() && !accessKeySecret.isBlank()
                && !phoneNumbers.isBlank() && !templateCode.isBlank();
    }

    @Override
    public boolean send(String title, String text) {
        try {
            String content = (title + "\n" + (text == null ? "" : text)).trim();
            if (content.length() > 500) content = content.substring(0, 500) + "…";
            SendSmsRequest req = new SendSmsRequest()
                    .setPhoneNumbers(phoneNumbers)
                    .setSignName(signName)
                    .setTemplateCode(templateCode)
                    .setTemplateParam("{\"content\":" + jsonEscape(content) + "}");
            var resp = client().sendSms(req);
            var result = resp.getBody();
            boolean ok = result != null && "OK".equals(result.getCode());
            if (!ok) {
                log.warn("[aliyun-sms] 短信发送失败：code={} message={}",
                        result == null ? "null" : result.getCode(),
                        result == null ? "" : result.getMessage());
            }
            return ok;
        } catch (Exception e) {
            log.warn("[aliyun-sms] 短信发送异常：{}", e.getMessage());
            return false;
        }
    }

    private static String jsonEscape(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
