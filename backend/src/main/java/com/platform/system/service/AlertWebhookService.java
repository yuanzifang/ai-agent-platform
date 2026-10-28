package com.platform.system.service;

import com.platform.system.alert.AliyunSmsAlertSender;
import com.platform.system.alert.AlertSender;
import com.platform.system.alert.EmailAlertSender;
import com.platform.system.alert.WebhookAlertSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维告警推送门面（多通道扇出）：配额/异常/健康事件、Alertmanager 路由 →
 * 全部启用通道（webhook=钉钉/企微/飞书、email、aliyun-sms）逐一投递。
 *
 * <p>公共 API 不变（alert(title, detail) / isConfigured()），调用方零改动。
 * 任一通道失败只影响自身，不阻断业务与其他通道。</p>
 */
@Slf4j
@Service
public class AlertWebhookService {

    private final List<AlertSender> senders;

    public AlertWebhookService(WebhookAlertSender webhookSender,
                               EmailAlertSender emailSender,
                               AliyunSmsAlertSender smsSender) {
        // 通道协作者可为 null（测试桩/未引入的可选依赖），容错过滤
        this.senders = java.util.stream.Stream.of(webhookSender, emailSender, smsSender)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** 是否至少配置了一个告警通道。 */
    public boolean isConfigured() {
        return senders.stream().anyMatch(AlertSender::enabled);
    }

    /** 统一入口：全通道扇出。返回各通道投递结果（未启用通道不出现在结果里）。 */
    public Map<String, Boolean> alertAll(String title, String detail) {
        String text = "【AI Agent 平台】" + title
                + (detail == null || detail.isBlank() ? "" : "\n" + detail)
                + "\n" + LocalDateTime.now();
        Map<String, Boolean> results = new LinkedHashMap<>();
        for (AlertSender sender : senders) {
            if (!sender.enabled()) continue;
            results.put(sender.name(), sender.send(title, text));
        }
        if (results.isEmpty()) {
            log.debug("无已启用的告警通道，跳过：{}", title);
        }
        return results;
    }

    /** 兼容旧签名：返回是否至少一个通道投递成功。 */
    public boolean alert(String title, String detail) {
        return alertAll(title, detail).containsValue(true);
    }

    /** 通道清单与启用状态（管理端/测试端点展示）。 */
    public List<Map<String, Object>> channelStatus() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AlertSender sender : senders) {
            list.add(Map.of("channel", sender.name(), "enabled", sender.enabled()));
        }
        return list;
    }

    /** 兼容旧调用：webhook 通道的配置状态。 */
    public boolean webhookEnabled() {
        return senders.stream()
                .filter(s -> s instanceof WebhookAlertSender)
                .findFirst().map(AlertSender::enabled).orElse(false);
    }
}
