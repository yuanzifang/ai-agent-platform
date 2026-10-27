package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MySQL knowledge_chunk -> Elasticsearch 的索引构建与 outbox 重放。 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "search.keyword.provider", havingValue = "es")
public class KnowledgeSearchIndexService {

    private final @Qualifier("jdbcTemplate") JdbcTemplate mysqlJdbcTemplate;
    private final ElasticsearchClient client;
    private final ObjectMapper objectMapper;

    public void indexChunk(Long tenantId, Long chunkId) {
        List<Map<String, Object>> rows = mysqlJdbcTemplate.queryForList("""
                SELECT kc.id, kc.document_id, kc.knowledge_base_id, kc.chunk_index, kc.parent_index,
                       kc.document_type, kc.section_type, kc.chunk_level, kc.parent_key,
                       kc.domain_keywords, kc.relevance_score, kc.regulatory_category,
                       kc.content, kc.allowed_users, kc.tenant_visible, kc.page_from, kc.page_to,
                       kd.effective_from, kd.effective_to
                FROM knowledge_chunk kc
                LEFT JOIN knowledge_document kd ON kd.id = kc.document_id
                WHERE kc.id = ? AND kc.tenant_id = ?
                """, chunkId, tenantId);
        if (rows.isEmpty()) {
            client.deleteDocument(documentId(tenantId, chunkId));
            return;
        }
        Map<String, Object> row = rows.get(0);
        String metadata = metadata(row);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("content", row.get("content"));
        source.put("chunk_id", row.get("id"));
        source.put("document_id", row.get("document_id"));
        source.put("knowledge_base_id", String.valueOf(row.get("knowledge_base_id")));
        source.put("chunk_index", row.get("chunk_index"));
        source.put("metadata", metadata);
        source.put("document_type", row.get("document_type"));
        source.put("section_type", row.get("section_type"));
        source.put("chunk_level", row.get("chunk_level"));
        source.put("parent_key", row.get("parent_key"));
        source.put("domain_keywords", row.get("domain_keywords"));
        source.put("relevance_score", row.get("relevance_score"));
        source.put("regulatory_category", row.get("regulatory_category"));
        source.put("page_from", row.get("page_from"));
        source.put("page_to", row.get("page_to"));
        source.put("allowed_users", allowedUsers(row.get("allowed_users")));
        source.put("tenant_visible", asBoolean(row.get("tenant_visible")));
        source.put("effective_from", iso(row.get("effective_from")));
        source.put("effective_to", iso(row.get("effective_to")));
        source.put("tenant_id", tenantId);
        client.indexDocument(documentId(tenantId, chunkId), source);
    }

    public void deleteChunk(Long tenantId, Long chunkId) {
        client.deleteDocument(documentId(tenantId, chunkId));
    }

    /** 手动把知识库现有分段放入 outbox；worker 会异步建索引。 */
    public int enqueueKnowledgeBase(Long tenantId, Long knowledgeBaseId) {
        return mysqlJdbcTemplate.update("""
                INSERT INTO knowledge_search_outbox
                    (tenant_id, chunk_id, operation, status, attempts, available_at, created_at, updated_at)
                SELECT kc.tenant_id, kc.id, 'UPSERT', 'PENDING', 0, NOW(), NOW(), NOW()
                FROM knowledge_chunk kc
                WHERE kc.tenant_id = ? AND kc.knowledge_base_id = ?
                ON DUPLICATE KEY UPDATE operation='UPSERT', status='PENDING',
                    attempts=0, available_at=NOW(), updated_at=NOW()
                """, tenantId, knowledgeBaseId);
    }

    /** 首次启用 ES 时把现有分段全部排入 outbox。 */
    public int enqueueAll() {
        return mysqlJdbcTemplate.update("""
                INSERT INTO knowledge_search_outbox
                    (tenant_id, chunk_id, operation, status, attempts, available_at, created_at, updated_at)
                SELECT kc.tenant_id, kc.id, 'UPSERT', 'PENDING', 0, NOW(), NOW(), NOW()
                FROM knowledge_chunk kc
                ON DUPLICATE KEY UPDATE operation='UPSERT', status='PENDING',
                    attempts=0, available_at=NOW(), updated_at=NOW()
                """);
    }

    private String metadata(Map<String, Object> row) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("chunk_id", row.get("id"));
        meta.put("document_id", row.get("document_id"));
        meta.put("knowledge_base_id", String.valueOf(row.get("knowledge_base_id")));
        meta.put("chunk_index", row.get("chunk_index"));
        meta.put("parent_index", row.get("parent_index"));
        meta.put("document_type", row.get("document_type"));
        meta.put("section_type", row.get("section_type"));
        meta.put("chunk_level", row.get("chunk_level"));
        meta.put("parent_key", row.get("parent_key"));
        meta.put("domain_keywords", row.get("domain_keywords"));
        meta.put("relevance_score", row.get("relevance_score"));
        meta.put("regulatory_category", row.get("regulatory_category"));
        meta.put("allowed_users", allowedUsers(row.get("allowed_users")));
        meta.put("tenant_visible", asBoolean(row.get("tenant_visible")));
        meta.put("effective_from", iso(row.get("effective_from")));
        meta.put("effective_to", iso(row.get("effective_to")));
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            return "{}";
        }
    }

    private List<String> allowedUsers(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.readValue(String.valueOf(value), List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        return value != null && ("1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value)));
    }

    private String iso(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp ts) return ts.toInstant().toString();
        if (value instanceof LocalDateTime ldt) return ldt.toInstant(ZoneOffset.UTC).toString();
        return String.valueOf(value);
    }

    private String documentId(Long tenantId, Long chunkId) {
        return tenantId + ":" + chunkId;
    }
}
