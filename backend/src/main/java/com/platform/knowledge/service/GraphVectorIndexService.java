package com.platform.knowledge.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GraphRAG 专用图向量索引（pgvector）。
 *
 * <p>实体/社区摘要按 embedding 维度分表（graph_vector_index_{dim}），避免固定 1024 维
 * 限制其他模型；不同维度互不影响，查询失败时调用方回退 MySQL cosine。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphVectorIndexService {

    private final @Qualifier("pgJdbcTemplate") JdbcTemplate pgJdbcTemplate;

    @Value("${pgvector.graph-index.enabled:true}")
    private boolean enabled = true;

    private final Set<Integer> readyDimensions = ConcurrentHashMap.newKeySet();

    public boolean enabled() {
        return enabled;
    }

    public void upsert(Long tenantId, Long kbId, String objectType, Long objectId,
                       float[] vector, String model) {
        if (!enabled || vector == null || vector.length == 0) return;
        try {
            int dimensions = vector.length;
            ensure(dimensions);
            pgJdbcTemplate.update("""
                    INSERT INTO %s
                        (tenant_id, kb_id, object_type, object_id, embedding, model, updated_at)
                    VALUES (?, ?, ?, ?, ?::vector, ?, now())
                    ON CONFLICT (tenant_id, kb_id, object_type, object_id)
                    DO UPDATE SET embedding=EXCLUDED.embedding, model=EXCLUDED.model,
                                  updated_at=now()
                    """.formatted(table(dimensions)),
                    tenantId, kbId, objectType, objectId, vectorLiteral(vector), model);
        } catch (Exception e) {
            log.debug("图向量写入失败 tenant={} kb={} type={} id={}: {}",
                    tenantId, kbId, objectType, objectId, e.getMessage());
        }
    }

    public void delete(Long tenantId, Long kbId, String objectType, Long objectId,
                       Integer dimensions) {
        if (!enabled || dimensions == null || dimensions <= 0) return;
        try {
            ensure(dimensions);
            pgJdbcTemplate.update("""
                    DELETE FROM %s
                    WHERE tenant_id=? AND kb_id=? AND object_type=? AND object_id=?
                    """.formatted(table(dimensions)), tenantId, kbId, objectType, objectId);
        } catch (Exception e) {
            log.debug("图向量删除失败 tenant={} kb={} type={} id={}: {}",
                    tenantId, kbId, objectType, objectId, e.getMessage());
        }
    }

    public List<Map<String, Object>> search(Long tenantId, Long kbId, String objectType,
                                            float[] vector, int limit) {
        if (!enabled || vector == null || vector.length == 0) return List.of();
        try {
            int dimensions = vector.length;
            ensure(dimensions);
            String literal = vectorLiteral(vector);
            return pgJdbcTemplate.query("""
                    SELECT object_id, 1 - (embedding <=> ?::vector) AS score
                    FROM %s
                    WHERE tenant_id=? AND kb_id=? AND object_type=?
                    ORDER BY embedding <=> ?::vector
                    LIMIT ?
                    """.formatted(table(dimensions)),
                    (rs, rowNum) -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("objectId", rs.getLong("object_id"));
                        row.put("score", rs.getDouble("score"));
                        return row;
                    }, literal, tenantId, kbId, objectType, literal,
                    Math.max(1, Math.min(limit, 50)));
        } catch (Exception e) {
            log.debug("图向量检索失败 tenant={} kb={} type={}: {}",
                    tenantId, kbId, objectType, e.getMessage());
            return List.of();
        }
    }

    private void ensure(int dimensions) {
        if (readyDimensions.contains(dimensions)) return;
        synchronized (readyDimensions) {
            if (readyDimensions.contains(dimensions)) return;
            String table = table(dimensions);
            pgJdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
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
                    """.formatted(table, dimensions));
            pgJdbcTemplate.execute("""
                    CREATE INDEX IF NOT EXISTS %s
                    ON %s USING hnsw (embedding vector_cosine_ops)
                    """.formatted("idx_" + table + "_hnsw", table));
            readyDimensions.add(dimensions);
        }
    }

    private String table(int dimensions) {
        return "graph_vector_index_" + dimensions;
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
