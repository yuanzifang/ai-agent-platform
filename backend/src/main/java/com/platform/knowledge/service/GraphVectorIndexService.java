package com.platform.knowledge.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GraphRAG 专用图向量索引（pgvector）。
 *
 * <p>实体/社区摘要共用一张表，用 object_type 区分；当前默认 embedding 维度固定，
 * 与 embedding.dimensions 不一致的模型不会写入该索引，调用方回退 MySQL cosine。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphVectorIndexService {

    private final @Qualifier("pgJdbcTemplate") JdbcTemplate pgJdbcTemplate;
    private final EmbeddingProperties embeddingProperties;

    @Value("${pgvector.graph-index.enabled:true}")
    private boolean enabled = true;

    private volatile boolean ready;

    public boolean enabled() {
        return enabled;
    }

    public void upsert(Long tenantId, Long kbId, String objectType, Long objectId,
                       float[] vector, String model) {
        if (!enabled || vector == null || vector.length != embeddingProperties.getDimensions()) return;
        try {
            ensure();
            pgJdbcTemplate.update("""
                    INSERT INTO graph_vector_index
                        (tenant_id, kb_id, object_type, object_id, embedding, model, updated_at)
                    VALUES (?, ?, ?, ?, ?::vector, ?, now())
                    ON CONFLICT (tenant_id, kb_id, object_type, object_id)
                    DO UPDATE SET embedding=EXCLUDED.embedding, model=EXCLUDED.model,
                                  updated_at=now()
                    """, tenantId, kbId, objectType, objectId, vectorLiteral(vector), model);
        } catch (Exception e) {
            log.debug("图向量写入失败 tenant={} kb={} type={} id={}: {}",
                    tenantId, kbId, objectType, objectId, e.getMessage());
        }
    }

    public List<Map<String, Object>> search(Long tenantId, Long kbId, String objectType,
                                            float[] vector, int limit) {
        if (!enabled || vector == null || vector.length != embeddingProperties.getDimensions()) {
            return List.of();
        }
        try {
            ensure();
            String literal = vectorLiteral(vector);
            return pgJdbcTemplate.query("""
                    SELECT object_id, 1 - (embedding <=> ?::vector) AS score
                    FROM graph_vector_index
                    WHERE tenant_id=? AND kb_id=? AND object_type=?
                    ORDER BY embedding <=> ?::vector
                    LIMIT ?
                    """, (rs, rowNum) -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("objectId", rs.getLong("object_id"));
                        row.put("score", rs.getDouble("score"));
                        return row;
                    }, literal, tenantId, kbId, objectType, literal, Math.max(1, Math.min(limit, 50)));
        } catch (Exception e) {
            log.debug("图向量检索失败 tenant={} kb={} type={}: {}", tenantId, kbId, objectType, e.getMessage());
            return List.of();
        }
    }

    private void ensure() {
        if (ready) return;
        synchronized (this) {
            if (ready) return;
            pgJdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS graph_vector_index (
                        id BIGSERIAL PRIMARY KEY,
                        tenant_id BIGINT NOT NULL,
                        kb_id BIGINT NOT NULL,
                        object_type VARCHAR(16) NOT NULL,
                        object_id BIGINT NOT NULL,
                        embedding vector(%d) NOT NULL,
                        model VARCHAR(64) NULL,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        UNIQUE (tenant_id, kb_id, object_type, object_id)
                    )
                    """.formatted(embeddingProperties.getDimensions()));
            pgJdbcTemplate.execute("""
                    CREATE INDEX IF NOT EXISTS idx_graph_vector_hnsw
                    ON graph_vector_index USING hnsw (embedding vector_cosine_ops)
                    """);
            ready = true;
        }
    }

    private String vectorLiteral(float[] vector) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) out.append(',');
            out.append(vector[i]);
        }
        return out.append(']').toString();
    }
}
