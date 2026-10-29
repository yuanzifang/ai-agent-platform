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
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 轻量 Elasticsearch 8 REST 客户端，避免为单个适配器引入额外 SDK。 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "search.es.enabled", havingValue = "true")
public class ElasticsearchClient {

    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${search.es.url:http://127.0.0.1:9200}")
    private String baseUrl;
    @Value("${search.es.index:aap_knowledge_chunks_v1}")
    private String index;
    @Value("${search.es.alias:aap_knowledge_chunks}")
    private String alias;
    @Value("${search.es.replicas:0}")
    private int replicas;
    @Value("${search.es.api-key:}")
    private String apiKey;
    @Value("${search.es.username:}")
    private String username;
    @Value("${search.es.password:}")
    private String password;

    private volatile boolean ready;
    private volatile String searchTarget;
    private final AtomicInteger urlCursor = new AtomicInteger();

    public String index() {
        return index;
    }

    public void ensureIndex() {
        if (ready) return;
        synchronized (this) {
            if (ready) return;
            try {
                request("PUT", "/" + index, Map.of(
                        "settings", Map.of("number_of_shards", 1, "number_of_replicas", Math.max(0, replicas),
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
                    return;
                }
            }
            try {
                if (alias != null && !alias.isBlank() && !alias.equals(index)) {
                    ensureAlias();
                    searchTarget = alias;
                } else {
                    searchTarget = index;
                }
            } catch (Exception e) {
                // 没有 alias 管理权限时退回具体索引，不能让检索完全不可用。
                log.warn("ES alias 创建失败，退回索引 {}：{}", index, e.getMessage());
                searchTarget = index;
            }
            ready = true;
        }
    }

    private void ensureAlias() {
        JsonNode current = null;
        try {
            current = request("GET", "/_alias/" + alias, null);
        } catch (Exception e) {
            if (!String.valueOf(e.getMessage()).contains("404")) throw e;
        }

        java.util.List<Map<String, Object>> actions = new java.util.ArrayList<>();
        boolean pointsHere = false;
        if (current != null) {
            java.util.Iterator<String> names = current.fieldNames();
            while (names.hasNext()) {
                String existing = names.next();
                if (existing.equals(index)) {
                    pointsHere = true;
                } else {
                    actions.add(Map.of("remove", Map.of("index", existing, "alias", alias)));
                }
            }
        }
        if (pointsHere && actions.isEmpty()) return;
        actions.add(Map.of("add", Map.of("index", index, "alias", alias)));
        request("POST", "/_aliases", Map.of("actions", actions));
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

    /** 快照状态；由快照监控任务调用。 */
    public JsonNode snapshots(String repo) {
        return request("GET", "/_snapshot/" + repo + "/_all", null);
    }

    public JsonNode search(Map<String, Object> body) {
        ensureIndex();
        String target = searchTarget == null ? index : searchTarget;
        try {
            return request("POST", "/" + target + "/_search", body);
        } catch (Exception e) {
            if (!target.equals(index)) {
                log.warn("ES alias 查询失败，退回索引 {}：{}", index, e.getMessage());
                searchTarget = index;
                return request("POST", "/" + index + "/_search", body);
            }
            throw e;
        }
    }

    private JsonNode request(String method, String path, Object body) {
        List<String> urls = Arrays.stream(baseUrl.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
        if (urls.isEmpty()) throw new IllegalStateException("ES URL 为空");
        String payload;
        try {
            payload = body == null ? null : objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("ES 请求序列化失败 " + path, e);
        }
        int start = Math.floorMod(urlCursor.getAndIncrement(), urls.size());
        Exception last = null;
        for (int i = 0; i < urls.size(); i++) {
            String base = urls.get((start + i) % urls.size()).replaceAll("/+$", "");
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(base + path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json");
                if (apiKey != null && !apiKey.isBlank()) {
                    builder.header("Authorization", "ApiKey " + apiKey);
                } else if (username != null && !username.isBlank()) {
                    String basic = Base64.getEncoder().encodeToString(
                            (username + ":" + (password == null ? "" : password))
                                    .getBytes(StandardCharsets.UTF_8));
                    builder.header("Authorization", "Basic " + basic);
                }
                HttpRequest request = builder.method(method, payload == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(payload)).build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 300) {
                    String message = "ES " + response.statusCode() + ": " + response.body();
                    if (response.statusCode() == 429 || response.statusCode() >= 500) {
                        last = new IllegalStateException(message);
                        continue;
                    }
                    throw new IllegalStateException(message);
                }
                return objectMapper.readTree(response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("ES 请求被中断 " + path, e);
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IllegalStateException("ES 请求失败 " + path + "（所有 URL 均不可用）: "
                + (last == null ? "unknown" : last.getMessage()), last);
    }
}
