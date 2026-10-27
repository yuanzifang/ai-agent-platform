package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** ES 关键字检索实现；文档由 MySQL outbox 异步同步。 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "search.keyword.provider", havingValue = "es")
public class ElasticsearchKeywordSearchStore implements KeywordSearchStore {

    private final ElasticsearchClient client;
    private final ObjectMapper objectMapper;

    @Override
    public String provider() {
        return "es";
    }

    @Override
    public List<Map<String, Object>> search(Long tenantId, Long knowledgeBaseId, String query,
                                            int limit, Long userId, boolean tenantWide,
                                            Instant effectiveAfter, Instant effectiveBefore) {
        List<Object> filters = new ArrayList<>();
        filters.add(Map.of("term", Map.of("tenant_id", tenantId)));
        filters.add(Map.of("term", Map.of("knowledge_base_id", String.valueOf(knowledgeBaseId))));
        if (userId != null) {
            List<Object> acl = new ArrayList<>();
            acl.add(Map.of("bool", Map.of("must_not",
                    List.of(Map.of("exists", Map.of("field", "allowed_users"))))));
            acl.add(Map.of("term", Map.of("allowed_users", String.valueOf(userId))));
            if (tenantWide) {
                acl.add(Map.of("term", Map.of("tenant_visible", true)));
            }
            filters.add(Map.of("bool", Map.of("should", acl,
                    "minimum_should_match", 1)));
        }
        if (effectiveAfter != null) {
            filters.add(Map.of("bool", Map.of("should", List.of(
                    Map.of("bool", Map.of("must_not",
                            List.of(Map.of("exists", Map.of("field", "effective_to"))))),
                    Map.of("range", Map.of("effective_to",
                            Map.of("gte", effectiveAfter.toString())))
            ), "minimum_should_match", 1)));
        }
        if (effectiveBefore != null) {
            filters.add(Map.of("range", Map.of("effective_from",
                    Map.of("lte", effectiveBefore.toString()))));
        }
        Map<String, Object> body = Map.of(
                "size", limit,
                "query", Map.of("bool", Map.of(
                        "must", List.of(Map.of("match", Map.of("content", query))),
                        "filter", filters)),
                "_source", List.of("chunk_id", "document_id", "knowledge_base_id", "chunk_index",
                        "content", "metadata", "score", "document_type", "section_type",
                        "chunk_level", "parent_key", "domain_keywords", "relevance_score",
                        "regulatory_category", "page_from", "page_to"));
        JsonNode response = client.search(body);
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            Map<String, Object> source = objectMapper.convertValue(hit.path("_source"), Map.class);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", source.get("chunk_id"));
            row.put("document_id", source.get("document_id"));
            row.put("chunk_index", source.get("chunk_index"));
            row.put("document_type", source.get("document_type"));
            row.put("section_type", source.get("section_type"));
            row.put("chunk_level", source.get("chunk_level"));
            row.put("parent_key", source.get("parent_key"));
            row.put("domain_keywords", source.get("domain_keywords"));
            row.put("relevance_score", source.get("relevance_score"));
            row.put("regulatory_category", source.get("regulatory_category"));
            row.put("content", source.get("content"));
            row.put("page_from", source.get("page_from"));
            row.put("page_to", source.get("page_to"));
            row.put("metadata", source.get("metadata"));
            row.put("score", hit.path("_score").asDouble());
            out.add(row);
        }
        return out;
    }
}
