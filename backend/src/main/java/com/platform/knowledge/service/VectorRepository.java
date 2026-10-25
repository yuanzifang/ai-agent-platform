package com.platform.knowledge.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
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
    private final EmbeddingProperties embeddingProperties;

    public void ensureTenantSchema(Long tenantId) {
        String schema = schema(tenantId);
        pgJdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        for (int dim : embeddingProperties.models().values().stream().distinct().toList()) {
            String table = table(dim);
            pgJdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS %s.%s (
                        id BIGSERIAL PRIMARY KEY,
                        content TEXT NOT NULL,
                        embedding vector(%d) NOT NULL,
                        metadata JSONB NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )
                    """.formatted(schema, table, dim));
            // 兼容已存在的租户向量表：补 updated_at，并把 created_at 设为非空。
            pgJdbcTemplate.execute("ALTER TABLE %s.%s ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ"
                    .formatted(schema, table));
            pgJdbcTemplate.execute("ALTER TABLE %s.%s ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT now()"
                    .formatted(schema, table));
            pgJdbcTemplate.execute("ALTER TABLE %s.%s ALTER COLUMN created_at SET DEFAULT now()"
                    .formatted(schema, table));
            pgJdbcTemplate.execute("ALTER TABLE %s.%s ALTER COLUMN updated_at SET DEFAULT now()"
                    .formatted(schema, table));
            // 旧数据补时间：created_at 缺失用当前时间，updated_at 用 created_at；
            // 同时把时间写进 metadata，混合检索结果可直接读取。
            pgJdbcTemplate.execute("UPDATE %s.%s SET created_at = now() WHERE created_at IS NULL"
                    .formatted(schema, table));
            pgJdbcTemplate.execute("UPDATE %s.%s SET updated_at = created_at WHERE updated_at IS NULL"
                    .formatted(schema, table));
            pgJdbcTemplate.execute(("UPDATE %s.%s SET metadata = " +
                    "jsonb_set(jsonb_set(metadata, '{created_at}', to_jsonb(created_at)), " +
                    "'{updated_at}', to_jsonb(updated_at)) " +
                    "WHERE metadata->>'created_at' IS NULL OR metadata->>'updated_at' IS NULL")
                    .formatted(schema, table));
            pgJdbcTemplate.execute("ALTER TABLE %s.%s ALTER COLUMN created_at SET NOT NULL"
                    .formatted(schema, table));
            pgJdbcTemplate.execute("ALTER TABLE %s.%s ALTER COLUMN updated_at SET NOT NULL"
                    .formatted(schema, table));
            pgJdbcTemplate.execute("""
                    CREATE INDEX IF NOT EXISTS idx_%s_kbid ON %s.%s ((metadata->>'knowledge_base_id'))
                    """.formatted(table, schema, table));
            pgJdbcTemplate.execute("""
                    CREATE INDEX IF NOT EXISTS idx_%s_created_at ON %s.%s (created_at)
                    """.formatted(table, schema, table));
        }
    }

    /** 批量写入分段向量（默认维度）。 */
    public void saveBatch(Long tenantId, List<Map<String, Object>> rows) {
        saveBatch(tenantId, rows, embeddingProperties.getDimensions());
    }

    public void saveBatch(Long tenantId, List<Map<String, Object>> rows, int dimensions) {
        String table = "%s.%s".formatted(schema(tenantId), table(dimensions));
        rows.forEach(r -> pgJdbcTemplate.update(
                ("INSERT INTO %s (content, embedding, metadata, created_at, updated_at) " +
                        "VALUES (?, ?::vector, " +
                        "jsonb_set(jsonb_set(?::jsonb, '{created_at}', to_jsonb(now())), " +
                        "'{updated_at}', to_jsonb(now())), now(), now())")
                        .formatted(table),
                r.get("content"), vectorLiteral((float[]) r.get("embedding")), r.get("metadata")));
    }

    public List<Map<String, Object>> search(Long tenantId, float[] queryVector, Long knowledgeBaseId,
                                            int topK, double threshold) {
        return search(tenantId, queryVector, knowledgeBaseId, topK, threshold,
                embeddingProperties.getDimensions());
    }

    /**
     * 相似度检索：cosine（<=> 为距离，score = 1 - 距离），按 knowledge_base_id 过滤。
     */
    /** 权限变更时同步所有维度表的 ACL；旧数据缺失 ACL 时由检索 SQL 按知识库级权限兜底。 */
    public void updateAclForKnowledgeBase(Long tenantId, Long knowledgeBaseId,
                                          List<Long> allowedUsers, boolean tenantWide) {
        String usersJson = allowedUsers.stream().map(String::valueOf).map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        for (String table : tables()) {
            String sql = ("UPDATE %s.%s SET metadata = jsonb_set(jsonb_set(metadata, " +
                    "'{allowed_users}', ?::jsonb), '{tenant_visible}', ?::jsonb) " +
                    "WHERE metadata->>'knowledge_base_id' = ?")
                    .formatted(schema(tenantId), table);
            pgJdbcTemplate.update(sql, usersJson, String.valueOf(tenantWide), String.valueOf(knowledgeBaseId));
        }
    }

    /** 单 chunk 独立 ACL：同步所有维度表。 */
    public void updateAclForChunk(Long tenantId, Long knowledgeBaseId, Long chunkId,
                                  List<Long> allowedUsers, boolean tenantWide) {
        String usersJson = allowedUsers.stream().map(String::valueOf).map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        for (String table : tables()) {
            String sql = ("UPDATE %s.%s SET metadata = jsonb_set(jsonb_set(metadata, " +
                    "'{allowed_users}', ?::jsonb), '{tenant_visible}', ?::jsonb) " +
                    "WHERE metadata->>'knowledge_base_id' = ? AND metadata->>'chunk_id' = ?")
                    .formatted(schema(tenantId), table);
            pgJdbcTemplate.update(sql, usersJson, String.valueOf(tenantWide),
                    String.valueOf(knowledgeBaseId), String.valueOf(chunkId));
        }
    }

    public List<Map<String, Object>> search(Long tenantId, float[] queryVector, Long knowledgeBaseId,
                                            int topK, double threshold, int dimensions) {
        return search(tenantId, queryVector, knowledgeBaseId, topK, threshold, dimensions, null, true);
    }

    public List<Map<String, Object>> search(Long tenantId, float[] queryVector, Long knowledgeBaseId,
                                            int topK, double threshold, int dimensions,
                                            Long userId, boolean tenantWide) {
        return search(tenantId, queryVector, knowledgeBaseId, topK, threshold, dimensions,
                userId, tenantWide, null, null);
    }

    /** 在向量检索层按入库时间过滤，避免先取 topK 再过滤导致有效数据被截断。 */
    public List<Map<String, Object>> search(Long tenantId, float[] queryVector, Long knowledgeBaseId,
                                            int topK, double threshold, int dimensions,
                                            Long userId, boolean tenantWide,
                                            Instant createdAfter, Instant createdBefore) {
        String q = vectorLiteral(queryVector);
        Timestamp after = createdAfter == null ? null : Timestamp.from(createdAfter);
        Timestamp before = createdBefore == null ? null : Timestamp.from(createdBefore);
        String base = """
                SELECT id, content, metadata,
                       to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS created_at,
                       to_char(updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS updated_at,
                       1 - (embedding <=> ?::vector) AS score
                FROM %s.%s
                WHERE metadata->>'knowledge_base_id' = ?
                  AND 1 - (embedding <=> ?::vector) >= ?
                  AND (CAST(? AS timestamptz) IS NULL OR created_at >= CAST(? AS timestamptz))
                  AND (CAST(? AS timestamptz) IS NULL OR created_at <= CAST(? AS timestamptz))
                """.formatted(schema(tenantId), table(dimensions));
        if (userId == null) {
            return pgJdbcTemplate.queryForList(base + " ORDER BY embedding <=> ?::vector LIMIT ?",
                    q, String.valueOf(knowledgeBaseId), q, threshold, after, after, before, before,
                    q, topK);
        }
        return pgJdbcTemplate.queryForList(base + """
                  AND (metadata->'allowed_users' IS NULL
                       OR (? = TRUE AND metadata->>'tenant_visible' = 'true')
                       OR EXISTS (
                         SELECT 1 FROM jsonb_array_elements_text(
                           COALESCE(metadata->'allowed_users', '[]'::jsonb)) ua
                         WHERE ua = ?))
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """, q, String.valueOf(knowledgeBaseId), q, threshold, after, after, before, before,
                tenantWide, String.valueOf(userId), q, topK);
    }

    /** 人工修正图表内容后同步向量表内容与 embedding。 */
    public void updateContentForChunk(Long tenantId, Long knowledgeBaseId, Long chunkId,
                                      String content, float[] embedding, int dimensions) {
        String table = "%s.%s".formatted(schema(tenantId), table(dimensions));
        pgJdbcTemplate.update(
                "UPDATE %s SET content = ?, embedding = ?::vector, updated_at = now(), " +
                        "metadata = jsonb_set(metadata, '{updated_at}', to_jsonb(now())) " +
                        "WHERE metadata->>'knowledge_base_id' = ? AND metadata->>'chunk_id' = ?"
                        .formatted(table),
                content, vectorLiteral(embedding), String.valueOf(knowledgeBaseId),
                String.valueOf(chunkId));
    }

    public void deleteByDocument(Long tenantId, Long documentId) {
        for (String table : tables()) {
            pgJdbcTemplate.update(
                    "DELETE FROM %s.%s WHERE metadata->>'document_id' = ?"
                            .formatted(schema(tenantId), table),
                    String.valueOf(documentId));
        }
    }

    /** 删除知识库时级联清理全部维度向量（需求 3.6.4 / 架构 6.5）。 */
    public void deleteByKnowledgeBase(Long tenantId, Long knowledgeBaseId) {
        for (String table : tables()) {
            pgJdbcTemplate.update(
                    "DELETE FROM %s.%s WHERE metadata->>'knowledge_base_id' = ?"
                            .formatted(schema(tenantId), table),
                    String.valueOf(knowledgeBaseId));
        }
    }

    String table(int dimensions) {
        return dimensions == embeddingProperties.getDimensions()
                ? "embeddings" : "embeddings_" + dimensions;
    }

    private List<String> tables() {
        return embeddingProperties.models().values().stream().distinct()
                .map(this::table).toList();
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
