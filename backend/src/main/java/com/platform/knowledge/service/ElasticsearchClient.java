package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/** 轻量 Elasticsearch 8 REST 客户端，避免为单个适配器引入额外 SDK。 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "search.keyword.provider", havingValue = "es")
public class ElasticsearchClient {

    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${search.es.url:http://127.0.0.1:9200}")
    private String baseUrl;
    @Value("${search.es.index:aap_knowledge_chunks_v1}")
    private String index;
    @Value("${search.es.api-key:}")
    private String apiKey;
    @Value("${search.es.username:}")
    private String username;
    @Value("${search.es.password:}")
    private String password;

    public String index() {
        return index;
    }

    public void ensureIndex() {
        try {
            request("PUT", "/" + index, Map.of(
                    "settings", Map.of("number_of_shards", 1, "number_of_replicas", 0,
                            "analysis", Map.of(
                                    "tokenizer", Map.of("ngram_tokenizer", Map.of(
                                            "type", "ngram", "min_gram", 2, "max_gram", 3,
                                            "token_chars", java.util.List.of("letter", "digit"))),
                                    "analyzer", Map.of("ngram_analyzer", Map.of(
                                            "type", "custom", "tokenizer", "ngram_tokenizer",
                                            "filter", java.util.List.of("lowercase"))))),
                    "mappings", Map.of("properties", Map.ofEntries(
                            Map.entry("content", Map.of("type", "text",
                                    "analyzer", "ngram_analyzer", "search_analyzer", "ngram_analyzer")),
                            Map.entry("tenant_id", Map.of("type", "long")),
                            Map.entry("knowledge_base_id", Map.of("type", "keyword")),
                            Map.entry("document_id", Map.of("type", "keyword")),
                            Map.entry("chunk_id", Map.of("type", "long")),
                            Map.entry("allowed_users", Map.of("type", "keyword")),
                            Map.entry("tenant_visible", Map.of("type", "boolean")),
                            Map.entry("effective_from", Map.of("type", "date")),
                            Map.entry("effective_to", Map.of("type", "date")),
                            Map.entry("document_type", Map.of("type", "keyword")),
                            Map.entry("section_type", Map.of("type", "keyword")),
                            Map.entry("relevance_score", Map.of("type", "double"))))));
        } catch (Exception e) {
            if (String.valueOf(e.getMessage()).contains("resource_already_exists_exception")
                    || String.valueOf(e.getMessage()).contains("400")) {
                log.debug("ES index 已存在：{}", index);
            } else {
                log.warn("ES index 创建失败 index={} err={}", index, e.getMessage());
            }
        }
    }

    public void indexDocument(String id, Map<String, Object> source) {
        ensureIndex();
        request("PUT", "/" + index + "/_doc/"
                + URLEncoder.encode(id, StandardCharsets.UTF_8), source);
    }

    public void deleteDocument(String id) {
        try {
            request("DELETE", "/" + index + "/_doc/"
                    + URLEncoder.encode(id, StandardCharsets.UTF_8), null);
        } catch (Exception e) {
            if (!String.valueOf(e.getMessage()).contains("404")) {
                throw e;
            }
        }
    }

    public JsonNode search(Map<String, Object> body) {
        return request("POST", "/" + index + "/_search", body);
    }

    private JsonNode request(String method, String path, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json");
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "ApiKey " + apiKey);
            } else if (username != null && !username.isBlank()) {
                String basic = Base64.getEncoder().encodeToString(
                        (username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
                builder.header("Authorization", "Basic " + basic);
            }
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body));
            HttpRequest request = builder.method(method, publisher).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("ES " + response.statusCode() + ": " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("ES 请求失败 " + path + ": " + e.getMessage(), e);
        }
    }
}
