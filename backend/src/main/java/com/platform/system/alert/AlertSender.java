package com.platform.system.alert;

/**
 * 告警发送通道（钉钉/企微/飞书群机器人、邮件、阿里云短信…）。
 * 实现类注册为 Spring Bean 后由 AlertWebhookService 自动扇出；
 * enabled() 决定是否参与投递，send() 失败只影响自身通道、不影响其他通道。
 */
public interface AlertSender {

    /** 通道名（投递结果汇报用）：webhook / email / aliyun-sms。 */
    String name();

    /** 是否启用（未配置凭证/地址时为 false，静默跳过）。 */
    boolean enabled();

    /** 发送告警文本；返回是否成功投递。实现须自行兜异常，不外抛。 */
    boolean send(String title, String text);
}
