package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
 * Rerank 插件（Jina/Cohere/BGE 通用 HTTP 协议）：
 * POST {model, query, documents:[...], top_n} → {results:[{index, relevance_score}]}
 * 未配置/失败时返回原顺序，不影响检索可用性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RerankService {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${retrieval.rerank.enabled:false}")
    private boolean enabled;

    @Value("${retrieval.rerank.endpoint:}")
    private String endpoint;

    @Value("${retrieval.rerank.api-key:}")
    private String apiKey;

    @Value("${retrieval.rerank.model:}")
    private String model;

    @Value("${retrieval.rerank.top-n:5}")
    private int defaultTopN;

    public boolean enabled() {
        return enabled && endpoint != null && !endpoint.isBlank();
    }

    public List<Map<String, Object>> rerank(String query, List<Map<String, Object>> hits, int topN) {
        if (!enabled() || hits == null || hits.size() <= 1) return limit(hits, topN);
        int n = Math.max(1, Math.min(topN > 0 ? topN : defaultTopN, hits.size()));
        try {
            List<String> documents = hits.stream()
                    .map(h -> String.valueOf(h.getOrDefault("content", "")))
                    .toList();
            Map<String, Object> body = new LinkedHashMap<>();
            if (model != null && !model.isBlank()) body.put("model", model);
            body.put("query", query);
            body.put("documents", documents);
            body.put("top_n", n);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> resp = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("Rerank 服务返回 HTTP {}，按原顺序降级", resp.statusCode());
                return limit(hits, n);
            }
            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode results = root.path("results");
            if (!results.isArray()) return limit(hits, n);
            List<Map<String, Object>> reranked = new ArrayList<>();
            for (JsonNode result : results) {
                int index = result.path("index").asInt(-1);
                if (index < 0 || index >= hits.size()) continue;
                Map<String, Object> row = new LinkedHashMap<>(hits.get(index));
                JsonNode score = result.has("relevance_score")
                        ? result.get("relevance_score") : result.get("score");
                if (score != null && score.isNumber()) row.put("rerankScore", score.asDouble());
                reranked.add(row);
                if (reranked.size() >= n) break;
            }
            return reranked.isEmpty() ? limit(hits, n) : reranked;
        } catch (Exception e) {
            log.warn("Rerank 调用失败，按原顺序降级：{}", e.getMessage());
            return limit(hits, n);
        }
    }

    private List<Map<String, Object>> limit(List<Map<String, Object>> hits, int topN) {
        if (hits == null) return List.of();
        int n = Math.max(1, Math.min(topN > 0 ? topN : defaultTopN, hits.size()));
        return hits.size() <= n ? hits : new ArrayList<>(hits.subList(0, n));
    }
}
