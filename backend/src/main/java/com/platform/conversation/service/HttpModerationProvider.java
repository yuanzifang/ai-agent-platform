package com.platform.conversation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第三方审核 HTTP 适配器（可指向 Azure AI Content Safety 网关/阿里云/腾讯云/自建服务）：
 * POST {text,direction} → {hits:[{ruleId,action,matchedText,tip}]}。
 * 失败 fail-open 并告警，不阻断业务；启用：moderation.http.enabled=true。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "moderation.http.enabled", havingValue = "true")
public class HttpModerationProvider implements ContentModerationProvider {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    @Value("${moderation.http.endpoint:}")
    private String endpoint;

    @Value("${moderation.http.api-key:}")
    private String apiKey;

    @Value("${moderation.http.timeout-ms:3000}")
    private long timeoutMs;

    @Override
    public String name() {
        return "http";
    }

    @Override
    public List<ModerationService.Hit> check(String direction, String content) {
        if (endpoint == null || endpoint.isBlank() || content == null || content.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("text", content);
            body.put("direction", direction);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMillis(Math.max(500, timeoutMs)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> resp = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("第三方审核 HTTP {}，已放行：{}", resp.statusCode(), resp.body());
                return List.of();
            }
            JsonNode hitsNode = objectMapper.readTree(resp.body()).path("hits");
            List<ModerationService.Hit> hits = new ArrayList<>();
            if (hitsNode.isArray()) {
                for (JsonNode n : hitsNode) {
                    String action = n.path("action").asText("WARN").toUpperCase();
                    hits.add(new ModerationService.Hit(n.path("ruleId").asText("http"), action,
                            n.path("matchedText").asText(""), n.path("tip").asText("")));
                }
            }
            return hits;
        } catch (Exception e) {
            log.warn("第三方审核调用失败（放行）：{}", e.getMessage());
            return List.of();
        }
    }
}
