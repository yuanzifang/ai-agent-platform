package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Qdrant 向量存储适配器（REST，无额外 SDK 依赖）。
 *
 * <p>每个租户 + 维度一个 collection，便于按租户/维度独立扩容。Qdrant 的 payload
 * 保存原始 metadata 和 ACL/有效期字段；检索过滤在 Qdrant 服务端完成。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "vector.qdrant.enabled", havingValue = "true")
public class QdrantVectorStore implements VectorStore {

    private final EmbeddingProperties embeddingProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${vector.qdrant.url:http://127.0.0.1:6333}")
    private String baseUrl;
    @Value("${vector.qdrant.api-key:}")
    private String apiKey;
    @Value("${vector.qdrant.collection-prefix:aap}")
    private String collectionPrefix = "aap";

    @Override
    public String provider() {
        return "qdrant";
    }

    @Override
    public void ensureTenantSchema(Long tenantId) {
        for (int dim : embeddingProperties.models().values().stream().distinct().toList()) {
            ensureCollection(tenantId, dim);
        }
    }

    private void ensureCollection(Long tenantId, int dim) {
        String name = collection(tenantId, dim);
        try {
            request("PUT", "/collections/" + name, Map.of(
                    "vectors", Map.of("size", dim, "distance", "Cosine")));
        } catch (Exception e) {
            if (!String.valueOf(e.getMessage()).contains("409")
                    && !String.valueOf(e.getMessage()).toLowerCase().contains("already exists")) {
                log.warn("Qdrant collection 创建失败 tenant={} dim={} err={}", tenantId, dim, e.getMessage());
            }
        }
    }

    @Override
    public void saveBatch(Long tenantId, List<Map<String, Object>> rows) {
        saveBatch(tenantId, rows, embeddingProperties.getDimensions());
    }

    @Override
    public void saveBatch(Long tenantId, List<Map<String, Object>> rows, int dimensions) {
        if (rows == null || rows.isEmpty()) return;
        ensureCollection(tenantId, dimensions);
        List<Map<String, Object>> points = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> payload = payload(row);
            Long id = asLong(payload.get("chunk_id"));
            if (id == null) continue;
            points.add(Map.of("id", id, "vector", toList((float[]) row.get("embedding")),
                    "payload", payload));
        }
        if (points.isEmpty()) return;
        request("PUT", "/collections/" + collection(tenantId, dimensions) + "/points?wait=true",
                Map.of("points", points));
    }

    @Override
    public List<Map<String, Object>> search(Long tenantId, float[] queryVector, Long knowledgeBaseId,
                                            int topK, double threshold) {
        return search(tenantId, queryVector, knowledgeBaseId, topK, threshold,
                embeddingProperties.getDimensions());
    }

    @Override
    public List<Map<String, Object>> search(Long tenantId, float[] queryVector, Long knowledgeBaseId,
                                            int topK, double threshold, int dimensions) {
        return search(tenantId, queryVector, knowledgeBaseId, topK, threshold,
                dimensions, null, true, null, null);
    }

    @Override
    public List<Map<String, Object>> search(Long tenantId, float[] queryVector, Long knowledgeBaseId,
                                            int topK, double threshold, int dimensions,
                                            Long userId, boolean tenantWide) {
        return search(tenantId, queryVector, knowledgeBaseId, topK, threshold,
                dimensions, userId, tenantWide, null, null);
    }

    @Override
    public List<Map<String, Object>> search(Long tenantId, float[] queryVector, Long knowledgeBaseId,
                                            int topK, double threshold, int dimensions,
                                            Long userId, boolean tenantWide,
                                            Instant effectiveAfter, Instant effectiveBefore) {
        Map<String, Object> filter = filter(knowledgeBaseId, userId, tenantWide,
                effectiveAfter, effectiveBefore);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", toList(queryVector));
        body.put("limit", topK);
        body.put("with_payload", true);
        body.put("score_threshold", threshold);
        body.put("filter", filter);
        JsonNode resp = request("POST",
                "/collections/" + collection(tenantId, dimensions) + "/points/search", body);
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode point : resp.path("result")) {
            Map<String, Object> payload = objectMapper.convertValue(point.path("payload"), Map.class);
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("content", payload.getOrDefault("content", ""));
            hit.put("score", point.path("score").asDouble());
            hit.put("metadata", payload.get("metadata"));
            out.add(hit);
        }
        return out;
    }

    @Override
    public void updateAclForKnowledgeBase(Long tenantId, Long knowledgeBaseId,
                                          List<Long> allowedUsers, boolean tenantWide) {
        for (int dimensions : dimensions()) {
            updatePayloadForFilter(tenantId, filter(knowledgeBaseId, null, true, null, null),
                    aclPayload(allowedUsers, tenantWide), dimensions);
        }
    }

    @Override
    public void updateAclForChunk(Long tenantId, Long knowledgeBaseId, Long chunkId,
                                  List<Long> allowedUsers, boolean tenantWide) {
        Map<String, Object> f = Map.of("must", List.of(
                term("knowledge_base_id", String.valueOf(knowledgeBaseId)),
                term("chunk_id", String.valueOf(chunkId))));
        for (int dimensions : dimensions()) {
            updatePayloadForFilter(tenantId, f, aclPayload(allowedUsers, tenantWide), dimensions);
        }
    }

    @Override
    public void updateContentForChunk(Long tenantId, Long knowledgeBaseId, Long chunkId,
                                      String content, float[] embedding, int dimensions) {
        Map<String, Object> f = Map.of("must", List.of(
                term("knowledge_base_id", String.valueOf(knowledgeBaseId)),
                term("chunk_id", String.valueOf(chunkId))));
        updatePayloadForFilter(tenantId, f, Map.of("content", content), dimensions);
        if (embedding != null) {
            request("PUT", "/collections/" + collection(tenantId, dimensions) + "/points/vectors?wait=true",
                    Map.of("points", List.of(Map.of("id", chunkId, "vector", toList(embedding)))));
        }
    }

    @Override
    public void updateValidityForDocument(Long tenantId, Long knowledgeBaseId, Long documentId,
                                          Timestamp effectiveFrom, Timestamp effectiveTo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("effective_from_ts", effectiveFrom == null ? 0L : effectiveFrom.toInstant().toEpochMilli());
        payload.put("effective_to_ts", effectiveTo == null ? Long.MAX_VALUE : effectiveTo.toInstant().toEpochMilli());
        Map<String, Object> f = Map.of("must", List.of(
                term("knowledge_base_id", String.valueOf(knowledgeBaseId)),
                term("document_id", String.valueOf(documentId))));
        for (int dimensions : dimensions()) {
            updatePayloadForFilter(tenantId, f, payload, dimensions);
        }
    }

    @Override
    public int copyDocumentVectors(Long tenantId, Long fromDocumentId, Long toDocumentId,
                                   int dimensions, String embeddingModel,
                                   Map<Integer, Long> chunkIdByIndex,
                                   Map<String, Object> metadataOverrides) {
        Map<String, Object> filter = Map.of("must", List.of(
                term("document_id", String.valueOf(fromDocumentId))));
        JsonNode resp = request("POST", "/collections/" + collection(tenantId, dimensions) + "/points/scroll",
                Map.of("filter", filter, "limit", 1000, "with_payload", true, "with_vector", true));
        List<Map<String, Object>> points = new ArrayList<>();
        for (JsonNode point : resp.path("result").path("points")) {
            Map<String, Object> payload = objectMapper.convertValue(point.path("payload"), Map.class);
            Integer chunkIndex = payload.get("chunk_index") instanceof Number n
                    ? n.intValue() : null;
            Long newId = chunkIndex == null ? null : chunkIdByIndex.get(chunkIndex);
            if (newId == null) continue;
            payload.put("document_id", String.valueOf(toDocumentId));
            payload.put("chunk_id", String.valueOf(newId));
            payload.put("metadata", updateMetadata(payload.get("metadata"), toDocumentId, newId,
                    metadataOverrides));
            if (metadataOverrides != null) payload.putAll(metadataOverrides);
            points.add(Map.of("id", newId, "vector", objectMapper.convertValue(
                    point.path("vector"), List.class), "payload", payload));
        }
        if (!points.isEmpty()) {
            request("PUT", "/collections/" + collection(tenantId, dimensions) + "/points?wait=true",
                    Map.of("points", points));
        }
        return points.size();
    }

    @Override
    public long countByDocument(Long tenantId, Long documentId, int dimensions) {
        JsonNode resp = request("POST", "/collections/" + collection(tenantId, dimensions) + "/points/count",
                Map.of("filter", Map.of("must", List.of(term("document_id", String.valueOf(documentId)))),
                        "exact", true));
        return resp.path("result").path("count").asLong(0);
    }

    @Override
    public void deleteByDocument(Long tenantId, Long documentId) {
        deleteByFilter(tenantId, Map.of("must", List.of(
                term("document_id", String.valueOf(documentId)))));
    }

    @Override
    public void deleteByKnowledgeBase(Long tenantId, Long knowledgeBaseId) {
        deleteByFilter(tenantId, Map.of("must", List.of(
                term("knowledge_base_id", String.valueOf(knowledgeBaseId)))));
    }

    private void deleteByFilter(Long tenantId, Map<String, Object> filter) {
        for (int dimensions : dimensions()) {
            request("POST", "/collections/" + collection(tenantId, dimensions)
                    + "/points/delete?wait=true", Map.of("filter", filter));
        }
    }

    private List<Integer> dimensions() {
        return embeddingProperties.models().values().stream().distinct().toList();
    }

    private void updatePayloadForFilter(Long tenantId, Map<String, Object> filter,
                                        Map<String, Object> payload, int dimensions) {
        request("POST", "/collections/" + collection(tenantId, dimensions) + "/points/payload?wait=true",
                Map.of("payload", payload, "filter", filter));
    }

    private Map<String, Object> filter(Long kbId, Long userId, boolean tenantWide,
                                       Instant after, Instant before) {
        List<Object> must = new ArrayList<>();
        must.add(term("knowledge_base_id", String.valueOf(kbId)));
        List<Object> should = new ArrayList<>();
        if (userId != null) {
            should.add(Map.of("is_empty", Map.of("key", "allowed_users")));
            should.add(Map.of("key", "allowed_users",
                    "match", Map.of("any", List.of(String.valueOf(userId)))));
            if (tenantWide) {
                should.add(Map.of("key", "tenant_visible", "match", Map.of("value", true)));
            }
        }
        if (after != null) {
            must.add(Map.of("range", Map.of("key", "effective_to_ts",
                    "gte", after.toEpochMilli())));
        }
        if (before != null) {
            must.add(Map.of("range", Map.of("key", "effective_from_ts",
                    "lte", before.toEpochMilli())));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("must", must);
        if (!should.isEmpty()) result.put("should", should);
        return result;
    }

    private Map<String, Object> term(String key, String value) {
        return Map.of("key", key, "match", Map.of("value", value));
    }

    private Map<String, Object> aclPayload(List<Long> allowedUsers, boolean tenantWide) {
        return Map.of("allowed_users", allowedUsers.stream().map(String::valueOf).toList(),
                "tenant_visible", tenantWide);
    }

    private Map<String, Object> payload(Map<String, Object> row) {
        String metadata = String.valueOf(row.get("metadata"));
        Map<String, Object> meta;
        try {
            meta = objectMapper.readValue(metadata, Map.class);
        } catch (Exception e) {
            meta = Map.of();
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("content", row.get("content"));
        p.put("metadata", metadata);
        p.put("chunk_id", string(meta.get("chunk_id")));
        p.put("document_id", string(meta.get("document_id")));
        p.put("knowledge_base_id", string(meta.get("knowledge_base_id")));
        p.put("chunk_index", meta.get("chunk_index"));
        p.put("allowed_users", meta.get("allowed_users"));
        p.put("tenant_visible", meta.get("tenant_visible"));
        Long from = epoch(row.get("effective_from"), meta.get("effective_from"));
        Long to = epoch(row.get("effective_to"), meta.get("effective_to"));
        p.put("effective_from_ts", from == null ? 0L : from);
        p.put("effective_to_ts", to == null ? Long.MAX_VALUE : to);
        return p;
    }

    private String updateMetadata(Object metadata, Long documentId, Long chunkId,
                                  Map<String, Object> overrides) {
        try {
            Map<String, Object> meta = metadata == null ? new LinkedHashMap<>()
                    : objectMapper.readValue(String.valueOf(metadata), Map.class);
            meta.put("document_id", documentId);
            meta.put("chunk_id", chunkId);
            if (overrides != null) meta.putAll(overrides);
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            return String.valueOf(metadata);
        }
    }

    private Long epoch(Object fromRow, Object fromMeta) {
        Object value = fromRow != null ? fromRow : fromMeta;
        if (value == null) return null;
        try {
            if (value instanceof Timestamp ts) return ts.toInstant().toEpochMilli();
            if (value instanceof Number n) return n.longValue();
            return Instant.parse(String.valueOf(value)).toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long asLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<Float> toList(float[] vector) {
        List<Float> out = new ArrayList<>(vector.length);
        for (float v : vector) out.add(v);
        return out;
    }

    private JsonNode request(String method, String path, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json");
            if (apiKey != null && !apiKey.isBlank()) builder.header("api-key", apiKey);
            HttpRequest request = builder.method(method, HttpRequest.BodyPublishers.ofString(
                    objectMapper.writeValueAsString(body))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("Qdrant " + response.statusCode() + ": " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("Qdrant 请求失败 " + path + ": " + e.getMessage(), e);
        }
    }

    private String collection(Long tenantId, int dimensions) {
        return collectionPrefix + "_" + tenantId + "_" + dimensions;
    }
}
