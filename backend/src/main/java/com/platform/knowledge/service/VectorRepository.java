package com.platform.knowledge.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * PgVector 向量存储（架构 2.4 / 6.5）：每租户独立 Schema（tenant_{id}），embeddings 表 vector(768)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorRepository {

    private final JdbcTemplate pgJdbcTemplate; // 由 PgVectorConfig 提供，指向 PostgreSQL

    @Value("${embedding.dimensions:768}")
    private int dimensions;

    public void ensureTenantSchema(Long tenantId) {
        String schema = schema(tenantId);
        pgJdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        pgJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS %s.embeddings (
                    id BIGSERIAL PRIMARY KEY,
                    content TEXT NOT NULL,
                    embedding vector(%d) NOT NULL,
                    metadata JSONB NOT NULL,
                    created_at TIMESTAMPTZ DEFAULT now()
                )
                """.formatted(schema, dimensions));
        pgJdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_embeddings_kbid ON %s.embeddings ((metadata->>'knowledge_base_id'))
                """.formatted(schema));
    }

    /** 批量写入分段向量。 */
    public void saveBatch(Long tenantId, List<Map<String, Object>> rows) {
        String schema = schema(tenantId);
        rows.forEach(r -> pgJdbcTemplate.update(
                "INSERT INTO %s.embeddings (content, embedding, metadata) VALUES (?, ?::vector, ?::jsonb)"
                        .formatted(schema),
                r.get("content"), vectorLiteral((float[]) r.get("embedding")), r.get("metadata")));
    }

    /**
     * 相似度检索：cosine（<=> 为距离，score = 1 - 距离），按 knowledge_base_id 过滤。
     */
    public List<Map<String, Object>> search(Long tenantId, float[] queryVector, Long knowledgeBaseId,
                                            int topK, double threshold) {
        String q = vectorLiteral(queryVector);
        return pgJdbcTemplate.queryForList("""
                SELECT id, content, metadata, 1 - (embedding <=> ?::vector) AS score
                FROM %s.embeddings
                WHERE metadata->>'knowledge_base_id' = ?
                  AND 1 - (embedding <=> ?::vector) >= ?
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """.formatted(schema(tenantId)),
                q, String.valueOf(knowledgeBaseId), q, threshold, q, topK);
    }

    public void deleteByDocument(Long tenantId, Long documentId) {
        pgJdbcTemplate.update(
                "DELETE FROM %s.embeddings WHERE metadata->>'document_id' = ?".formatted(schema(tenantId)),
                String.valueOf(documentId));
    }

    /** 删除知识库时级联清理全部向量（需求 3.6.4 / 架构 6.5）。 */
    public void deleteByKnowledgeBase(Long tenantId, Long knowledgeBaseId) {
        pgJdbcTemplate.update(
                "DELETE FROM %s.embeddings WHERE metadata->>'knowledge_base_id' = ?".formatted(schema(tenantId)),
                String.valueOf(knowledgeBaseId));
    }

    private String schema(Long tenantId) {
        return "tenant_" + tenantId;
    }

    private String vectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
