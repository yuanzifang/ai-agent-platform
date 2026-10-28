package com.platform.system.service;

import com.platform.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/** 平台后端到独立 alert-relay 的只读/ACK 代理；relay 不可用时返回可读的 503。 */
@Service
public class AlertRelayClient {

    @Value("${alert.relay.base-url:}")
    private String baseUrl;

    @Value("${alert.relay.ack-token:}")
    private String ackToken;

    public Map<String, Object> state() {
        return get("/state");
    }

    public Map<String, Object> ack(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new BusinessException(400, "fingerprint 不能为空");
        }
        try {
            return client().post()
                    .uri("/ack")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("fingerprint", fingerprint))
                    .retrieve()
                    .body(Map.class);
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new BusinessException(404, "待升级告警不存在或已处理");
            }
            throw new BusinessException(502, "告警 relay ACK 失败");
        } catch (Exception e) {
            throw new BusinessException(503, "告警 relay 不可用");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) {
        try {
            return client().get().uri(path).retrieve().body(Map.class);
        } catch (RestClientResponseException e) {
            throw new BusinessException(502, "告警 relay 返回异常");
        } catch (Exception e) {
            throw new BusinessException(503, "告警 relay 不可用");
        }
    }

    private RestClient client() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BusinessException(503, "告警 relay 未配置（ALERT_RELAY_BASE_URL）");
        }
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl.replaceAll("/+$", ""));
        if (ackToken != null && !ackToken.isBlank()) {
            builder = builder.defaultHeader("X-Ack-Token", ackToken);
        }
        return builder.build();
    }
}
