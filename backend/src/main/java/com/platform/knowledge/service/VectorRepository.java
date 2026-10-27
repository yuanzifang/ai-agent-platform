package com.platform.knowledge.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PgVector 向量存储（架构 2.4 / 6.5）：每租户独立 Schema（tenant_{id}），embeddings 表 vector(768)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate pgJdbcTemplate; // 由 PgVectorConfig 提供，指向 PostgreSQL
    private final EmbeddingProperties embeddingProperties;

    @Value("${pgvector.hnsw.enabled:true}")
    private boolean hnswEnabled = true;

    @Value("${pgvector.iterative-scan.enabled:true}")
    private boolean iterativeScanEnabled = true;

    @Value("${pgvector.hnsw.concurrently:true}")
    private boolean hnswConcurrently = true;

    @Value("${pgvector.filtered-exact.threshold:0.25}")
    private double filteredExactThreshold = 0.25;
    @Value("${pgvector.filtered-exact.min-rows:200000}")
    private long filteredExactMinRows = 200000;
    @Value("${pgvector.filtered-exact.max-rows:50000}")
    private long filteredExactMaxRows = 50000;

    @Value("${pgvector.hnsw.partial.enabled:true}")
    private boolean partialHnswEnabled = true;
    @Value("${pgvector.hnsw.partial.min-rows:50000}")
    private long partialHnswMinRows = 50000;
    @Value("${pgvector.hnsw.partial.max-indexes:20}")
    private int partialHnswMaxIndexes = 20;
    @Value("${pgvector.hnsw.partial.max-ratio:0.4}")
    private double partialHnswMaxRatio = 0.4;
    @Value("${pgvector.hnsw.partial.prune:false}")
    private boolean partialHnswPrune = false;

    private final Map<String, CachedCount> countCache = new ConcurrentHashMap<>();
    private final Map<String, CachedFlag> partialIndexCache = new ConcurrentHashMap<>();
    private static final long COUNT_CACHE_MS = 300_000;

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
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        effective_from TIMESTAMPTZ NOT NULL DEFAULT now(),
                        effective_to TIMESTAMPTZ NULL
                    )
                    """.formatted(schema, table, dim));
            // 兼容已存在的租户向量表：补 updated_at，并把 created_at 设为非空。
            pgJdbcTemplate.execute("ALTER TABLE %s.%s ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ"
                    .formatted(schema, table));
            pgJdbcTemplate.execute("ALTER TABLE %s.%s ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT now()"
                    .formatted(schema, table));
            pgJdbcTemplate.execute("ALTER TABLE %s.%s ADD COLUMN IF NOT EXISTS effective_from TIMESTAMPTZ"
                    .formatted(schema, table));
            pgJdbcTemplate.execute("ALTER TABLE %s.%s ADD COLUMN IF NOT EXISTS effective_to TIMESTAMPTZ"
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
            // 没有显式业务有效期时，用文档/向量已有时间回填，保证可直接按有效期筛选。
            pgJdbcTemplate.execute("UPDATE %s.%s SET effective_from = created_at WHERE effective_from IS NULL"
                    .formatted(schema, table));
            pgJdbcTemplate.execute("ALTER TABLE %s.%s ALTER COLUMN effective_from SET DEFAULT now()"
                    .formatted(schema, table));
            pgJdbcTemplate.execute("ALTER TABLE %s.%s ALTER COLUMN effective_from SET NOT NULL"
                    .formatted(schema, table));
            pgJdbcTemplate.execute(("UPDATE %s.%s SET metadata = " +
                    "jsonb_set(jsonb_set(jsonb_set(jsonb_set(metadata, '{created_at}', to_jsonb(created_at)), " +
                    "'{updated_at}', to_jsonb(updated_at)), '{effective_from}', to_jsonb(effective_from)), " +
                    "'{effective_to}', COALESCE(to_jsonb(effective_to), 'null'::jsonb)) " +
                    "WHERE metadata->>'created_at' IS NULL OR metadata->>'updated_at' IS NULL " +
                    "OR metadata->>'effective_from' IS NULL")
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
            pgJdbcTemplate.execute("""
                    CREATE INDEX IF NOT EXISTS idx_%s_effective ON %s.%s (effective_from, effective_to)
                    """.formatted(table, schema, table));
            if (hnswEnabled) {
                String create = """
                        CREATE INDEX %s IF NOT EXISTS idx_%s_hnsw ON %s.%s
                        USING hnsw (embedding vector_cosine_ops)
                        WITH (m = 16, ef_construction = 64)
                        """.formatted(hnswConcurrently ? "CONCURRENTLY" : "", table, schema, table);
                try {
                    pgJdbcTemplate.execute(create);
                } catch (Exception e) {
                    if (hnswConcurrently) {
                        // CONCURRENTLY 不能放在事务里；失败时回退普通建索引。
                        try {
                            pgJdbcTemplate.execute("""
                                    CREATE INDEX IF NOT EXISTS idx_%s_hnsw ON %s.%s
                                    USING hnsw (embedding vector_cosine_ops)
                                    WITH (m = 16, ef_construction = 64)
                                    """.formatted(table, schema, table));
                            continue;
                        } catch (Exception fallback) {
                            log.warn("HNSW 索引创建失败（跳过，继续使用精确扫描）schema={} table={} err={}",
                                    schema, table, fallback.getMessage());
                            continue;
                        }
                    }
                    // 旧版 pgvector 或低权限账号可能不支持 HNSW；检索仍可走精确扫描。
                    log.warn("HNSW 索引创建失败（跳过，继续使用精确扫描）schema={} table={} err={}",
                            schema, table, e.getMessage());
                }
            }
        }
    }

    /**
     * 为较大的知识库维护 partial HNSW 索引。
     *
     * <p>全局 HNSW + metadata 过滤在低选择比下会依赖 iterative scan，可能退化到秒级。
     * partial 索引让不同知识库各自拥有独立的 HNSW 图，查询只扫描命中的图。
     * 仅对行数达到阈值的知识库建索引，并限制每张维度表的索引数量，避免小知识库
     * 造成索引爆炸和写入放大；未覆盖的知识库继续使用全局 HNSW / 精确过滤回退。</p>
     *
     * @return created/dropped/desired 统计
     */
    public Map<String, Integer> reconcilePartialIndexes(Long tenantId) {
        if (!hnswEnabled || !partialHnswEnabled || partialHnswMaxIndexes <= 0) {
            return Map.of("created", 0, "dropped", 0, "desired", 0);
        }
        String schema = schema(tenantId);
        int created = 0;
        int dropped = 0;
        int desiredCount = 0;
        for (int dim : embeddingProperties.models().values().stream().distinct().toList()) {
            String table = table(dim);
            String prefix = partialIndexPrefix(table);
            try {
                Long total = pgJdbcTemplate.queryForObject(
                        "SELECT count(*) FROM %s.%s".formatted(schema, table), Long.class);
                long tableRows = total == null ? 0 : total;
                // 先取比上限更多的候选，再按命中占比过滤；50% 命中的知识库通常由全局 HNSW 更快，
                // 继续建 partial 只会占用磁盘。
                List<Map<String, Object>> rows = pgJdbcTemplate.queryForList("""
                        SELECT metadata->>'knowledge_base_id' AS kb_id, count(*) AS cnt
                        FROM %s.%s
                        WHERE metadata->>'knowledge_base_id' ~ '^[0-9]+$'
                        GROUP BY 1
                        HAVING count(*) >= ?
                        ORDER BY cnt DESC, kb_id
                        LIMIT ?
                        """.formatted(schema, table), partialHnswMinRows,
                        Math.max(1, partialHnswMaxIndexes * 4));
                LinkedHashMap<String, String> desired = new LinkedHashMap<>();
                for (Map<String, Object> row : rows) {
                    Object kb = row.get("kb_id");
                    if (kb == null) continue;
                    long count = longValue(row.get("cnt"));
                    if (partialHnswMaxRatio > 0 && tableRows > 0
                            && (double) count / tableRows > partialHnswMaxRatio) {
                        continue;
                    }
                    String kbId = String.valueOf(kb);
                    desired.put(kbId, partialIndexName(table, kbId));
                    if (desired.size() >= partialHnswMaxIndexes) break;
                }
                desiredCount += desired.size();
                List<String> existing = new java.util.ArrayList<>(pgJdbcTemplate.queryForList("""
                        SELECT indexname FROM pg_indexes
                        WHERE schemaname = ? AND tablename = ? AND indexname LIKE ?
                        """, String.class, schema, table, prefix + "%"));
                // CREATE INDEX CONCURRENTLY 被中断时会留下 invalid index；它不能参与查询，
                // 但名字已存在，必须先删掉，否则后续 IF NOT EXISTS 会一直跳过。
                List<String> invalid = pgJdbcTemplate.queryForList("""
                        SELECT c.relname FROM pg_class c
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        JOIN pg_index i ON i.indexrelid = c.oid
                        WHERE n.nspname = ? AND c.relname LIKE ? AND NOT i.indisvalid
                        """, String.class, schema, prefix + "%");
                for (String name : invalid) {
                    try {
                        dropPartialIndex(schema, name);
                        existing.remove(name);
                        dropped++;
                    } catch (Exception e) {
                        log.warn("invalid partial HNSW 索引删除失败 schema={} index={} err={}",
                                schema, name, e.getMessage());
                    }
                }
                for (Map.Entry<String, String> entry : desired.entrySet()) {
                    if (existing.contains(entry.getValue())) continue;
                    try {
                        createPartialIndex(schema, table, entry.getValue(), entry.getKey());
                        created++;
                    } catch (Exception e) {
                        log.warn("partial HNSW 索引创建失败 schema={} table={} kb={} err={}",
                                schema, table, entry.getKey(), e.getMessage());
                    }
                }
                if (partialHnswPrune) {
                    for (String name : existing) {
                        if (!desired.containsValue(name)) {
                            try {
                                dropPartialIndex(schema, name);
                                dropped++;
                            } catch (Exception e) {
                                log.warn("partial HNSW 索引删除失败 schema={} index={} err={}",
                                        schema, name, e.getMessage());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // 表可能尚未创建，或当前版本没有 partial HNSW 所需权限；检索仍可回退到精确扫描。
                log.debug("partial HNSW 维护跳过 schema={} table={} err={}",
                        schema, table, e.getMessage());
            }
        }
        partialIndexCache.clear();
        return Map.of("created", created, "dropped", dropped, "desired", desiredCount);
    }

    /** 查询指定知识库的全局/partial HNSW 状态，供运维页面和手动接口使用。 */
    public Map<String, Object> partialIndexStatus(Long tenantId, Long knowledgeBaseId, int dimensions) {
        String schema = schema(tenantId);
        String table = table(dimensions);
        String global = "idx_" + table + "_hnsw";
        String partial = partialIndexName(table, String.valueOf(knowledgeBaseId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tenantId);
        out.put("knowledgeBaseId", knowledgeBaseId);
        out.put("table", schema + "." + table);
        out.put("globalIndexName", global);
        out.put("partialIndexName", partial);
        try {
            List<Map<String, Object>> indexes = pgJdbcTemplate.queryForList("""
                    SELECT c.relname AS index_name, i.indisvalid AS valid,
                           pg_relation_size(c.oid) AS size_bytes
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    JOIN pg_index i ON i.indexrelid = c.oid
                    WHERE n.nspname = ? AND c.relname IN (?, ?)
                    ORDER BY c.relname
                    """, schema, global, partial);
            out.put("indexes", indexes);
            boolean present = indexes.stream()
                    .anyMatch(row -> partial.equals(String.valueOf(row.get("index_name")))
                            && Boolean.TRUE.equals(row.get("valid")));
            out.put("partialIndexPresent", present);
        } catch (Exception e) {
            out.put("indexes", List.of());
            out.put("partialIndexPresent", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    private void createPartialIndex(String schema, String table, String index, String kbId) {
        String create = """
                CREATE INDEX %s IF NOT EXISTS %s ON %s.%s
                USING hnsw (embedding vector_cosine_ops)
                WITH (m = 16, ef_construction = 64)
                WHERE metadata->>'knowledge_base_id' = '%s'
                """.formatted(hnswConcurrently ? "CONCURRENTLY" : "", index,
                schema, table, kbId);
        try {
            pgJdbcTemplate.execute(create);
        } catch (Exception e) {
            if (!hnswConcurrently) throw e;
            pgJdbcTemplate.execute("""
                    CREATE INDEX IF NOT EXISTS %s ON %s.%s
                    USING hnsw (embedding vector_cosine_ops)
                    WITH (m = 16, ef_construction = 64)
                    WHERE metadata->>'knowledge_base_id' = '%s'
                    """.formatted(index, schema, table, kbId));
        }
    }

    private void dropPartialIndex(String schema, String index) {
        pgJdbcTemplate.execute("DROP INDEX " + (hnswConcurrently ? "CONCURRENTLY " : "")
                + "IF EXISTS " + schema + "." + index);
    }

    private String partialIndexPrefix(String table) {
        return "idx_" + table + "_hnsw_kb_";
    }

    private String partialIndexName(String table, String kbId) {
        return partialIndexPrefix(table) + kbId;
    }

    /** 批量写入分段向量（默认维度）。 */
    public void saveBatch(Long tenantId, List<Map<String, Object>> rows) {
        saveBatch(tenantId, rows, embeddingProperties.getDimensions());
    }

    public void saveBatch(Long tenantId, List<Map<String, Object>> rows, int dimensions) {
        if (rows == null || rows.isEmpty()) return;
        String table = "%s.%s".formatted(schema(tenantId), table(dimensions));
        String sql = ("INSERT INTO %s (content, embedding, metadata, created_at, updated_at, " +
                "effective_from, effective_to) " +
                "VALUES (?, ?::vector, " +
                "jsonb_set(jsonb_set(?::jsonb, '{created_at}', to_jsonb(now())), " +
                "'{updated_at}', to_jsonb(now())), now(), now(), " +
                "COALESCE(?::timestamptz, now()), ?::timestamptz)")
                .formatted(table);
        List<Object[]> batch = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            batch.add(new Object[]{row.get("content"), vectorLiteral((float[]) row.get("embedding")),
                    row.get("metadata"), row.get("effective_from"), row.get("effective_to")});
        }
        for (int start = 0; start < batch.size(); start += 500) {
            pgJdbcTemplate.batchUpdate(sql, batch.subList(start, Math.min(batch.size(), start + 500)));
        }
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

    /** 在向量检索层按内容有效期区间过滤，避免先取 topK 再过滤导致有效数据被截断。 */
    public List<Map<String, Object>> search(Long tenantId, float[] queryVector, Long knowledgeBaseId,
                                            int topK, double threshold, int dimensions,
                                            Long userId, boolean tenantWide,
                                            Instant effectiveAfter, Instant effectiveBefore) {
        enableIterativeScan();
        String q = vectorLiteral(queryVector);
        Timestamp after = effectiveAfter == null ? null : Timestamp.from(effectiveAfter);
        Timestamp before = effectiveBefore == null ? null : Timestamp.from(effectiveBefore);
        // 使用字面量而不是绑定参数，PostgreSQL 才能把查询条件匹配到
        // WHERE metadata->>'knowledge_base_id' = '<id>' 的 partial HNSW 索引。
        String kbPredicate = "metadata->>'knowledge_base_id' = '" + knowledgeBaseId + "'";
        String base = """
                SELECT id, content, metadata,
                       to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS created_at,
                       to_char(updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS updated_at,
                       to_char(effective_from AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS effective_from,
                       to_char(effective_to AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS effective_to,
                       1 - (embedding <=> ?::vector) AS score
                FROM %s.%s
                WHERE %s
                  AND 1 - (embedding <=> ?::vector) >= ?
                  AND (CAST(? AS timestamptz) IS NULL OR effective_to IS NULL
                       OR effective_to >= CAST(? AS timestamptz))
                  AND (CAST(? AS timestamptz) IS NULL OR effective_from <= CAST(? AS timestamptz))
                """.formatted(schema(tenantId), table(dimensions), kbPredicate);
        if (shouldUseExact(tenantId, knowledgeBaseId, dimensions)) {
            String exactBase = userId == null ? base : base + """
                      AND (metadata->'allowed_users' IS NULL
                           OR (? = TRUE AND metadata->>'tenant_visible' = 'true')
                           OR EXISTS (
                             SELECT 1 FROM jsonb_array_elements_text(
                               COALESCE(metadata->'allowed_users', '[]'::jsonb)) ua
                             WHERE ua = ?))
                    """;
            String sql = "WITH filtered AS MATERIALIZED (" + exactBase +
                    ") SELECT * FROM filtered ORDER BY score DESC LIMIT ?";
            if (userId == null) {
                return pgJdbcTemplate.queryForList(sql, q, q,
                        threshold, after, after, before, before, topK);
            }
            return pgJdbcTemplate.queryForList(sql, q, q,
                    threshold, after, after, before, before, tenantWide, String.valueOf(userId), topK);
        }
        if (userId == null) {
            return pgJdbcTemplate.queryForList(base + " ORDER BY embedding <=> ?::vector LIMIT ?",
                    q, q, threshold, after, after, before, before, q, topK);
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
                """, q, q, threshold, after, after, before, before,
                tenantWide, String.valueOf(userId), q, topK);
    }

    /** 低选择比时物化过滤集再精确排序，避免 HNSW iterative scan 扫描大量不匹配向量。 */
    private boolean shouldUseExact(Long tenantId, Long knowledgeBaseId, int dimensions) {
        // 已有该知识库的 partial HNSW 时优先使用专用索引；否则仍走原来的精确过滤策略。
        if (partialHnswEnabled && partialHnswMaxIndexes > 0
                && hasPartialIndex(tenantId, knowledgeBaseId, dimensions)) {
            return false;
        }
        try {
            String table = "%s.%s".formatted(schema(tenantId), table(dimensions));
            long total = cachedCount("total:" + table,
                    () -> pgJdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class));
            long kb = cachedCount("kb:" + table + ":" + knowledgeBaseId,
                    () -> pgJdbcTemplate.queryForObject(
                            "SELECT count(*) FROM " + table + " WHERE metadata->>'knowledge_base_id' = ?",
                            Long.class, String.valueOf(knowledgeBaseId)));
            // 命中行数过多时物化过滤集本身就要扫描/排序大量 1024 维向量
            // （1M/20% 实测秒级），即使占比低也应交给 HNSW；精确回退只用于小知识库。
            if (filteredExactMaxRows > 0 && kb > filteredExactMaxRows) return false;
            return total >= filteredExactMinRows && kb > 0
                    && (double) kb / total < filteredExactThreshold;
        } catch (Exception e) {
            log.debug("向量过滤比估算失败，继续 HNSW：{}", e.getMessage());
            return false;
        }
    }

    private boolean hasPartialIndex(Long tenantId, Long knowledgeBaseId, int dimensions) {
        String schema = schema(tenantId);
        String table = table(dimensions);
        String index = partialIndexName(table, String.valueOf(knowledgeBaseId));
        String key = schema + "." + table + ":" + index;
        long now = System.currentTimeMillis();
        CachedFlag cached = partialIndexCache.get(key);
        if (cached != null && now - cached.at() < COUNT_CACHE_MS) return cached.valid();
        boolean valid = false;
        try {
            Long count = pgJdbcTemplate.queryForObject("""
                    SELECT count(*) FROM pg_index i
                    JOIN pg_class c ON c.oid = i.indexrelid
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND c.relname = ? AND i.indisvalid
                    """, Long.class, schema, index);
            valid = count != null && count > 0;
        } catch (Exception e) {
            log.debug("partial HNSW 索引探测失败 schema={} index={} err={}",
                    schema, index, e.getMessage());
        }
        partialIndexCache.put(key, new CachedFlag(valid, now));
        return valid;
    }

    private long cachedCount(String key, java.util.function.Supplier<Long> query) {
        CachedCount cached = countCache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < COUNT_CACHE_MS) return cached.value();
        Long value = query.get();
        long count = value == null ? 0 : value;
        countCache.put(key, new CachedCount(count, now));
        return count;
    }

    private record CachedCount(long value, long at) { }
    private record CachedFlag(boolean valid, long at) { }

    /** HNSW 过滤检索配合 iterative scan，避免 metadata 过滤后结果不足。 */
    private void enableIterativeScan() {
        if (!iterativeScanEnabled) return;
        try {
            pgJdbcTemplate.execute("SET hnsw.iterative_scan = relaxed_order");
        } catch (Exception e) {
            log.debug("pgvector iterative scan 不可用（继续精确扫描）：{}", e.getMessage());
        }
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

    /** 修改文档有效期时同步所有维度表的向量有效期与 metadata，无需重新解析。 */
    public void updateValidityForDocument(Long tenantId, Long knowledgeBaseId, Long documentId,
                                          Timestamp effectiveFrom, Timestamp effectiveTo) {
        for (String table : tables()) {
            String sql = ("UPDATE %s.%s SET effective_from = ?, effective_to = ?, updated_at = now(), " +
                    "metadata = jsonb_set(jsonb_set(metadata, '{effective_from}', " +
                    "to_jsonb(?::timestamptz)), '{effective_to}', " +
                    "COALESCE(to_jsonb(?::timestamptz), 'null'::jsonb)) " +
                    "WHERE metadata->>'knowledge_base_id' = ? AND metadata->>'document_id' = ?")
                    .formatted(schema(tenantId), table);
            pgJdbcTemplate.update(sql, effectiveFrom, effectiveTo, effectiveFrom, effectiveTo,
                    String.valueOf(knowledgeBaseId), String.valueOf(documentId));
        }
    }

    /**
     * 复用相同文件的分段向量：复制已有文档的 embedding，替换 document_id/chunk_id
     * 以及新文档的 ACL、文件名、有效期 metadata。
     *
     * @return 复制的向量行数；0 表示没有可复用数据；抛异常时调用方回退正常解析
     */
    @SuppressWarnings("unchecked")
    public int copyDocumentVectors(Long tenantId, Long fromDocumentId, Long toDocumentId,
                                   int dimensions, String embeddingModel,
                                   Map<Integer, Long> chunkIdByIndex,
                                   Map<String, Object> metadataOverrides) {
        String table = "%s.%s".formatted(schema(tenantId), table(dimensions));
        List<Map<String, Object>> rows = pgJdbcTemplate.queryForList(
                ("SELECT content, embedding::text AS embedding, metadata, " +
                        "effective_from, effective_to FROM %s.%s " +
                        "WHERE metadata->>'document_id' = ?")
                        .formatted(schema(tenantId), table),
                String.valueOf(fromDocumentId));
        if (rows == null || rows.isEmpty()) return 0;
        String sql = ("INSERT INTO %s (content, embedding, metadata, created_at, updated_at, " +
                "effective_from, effective_to) VALUES (?, ?::vector, ?::jsonb, now(), now(), " +
                "COALESCE(?::timestamptz, now()), ?::timestamptz)").formatted(table);
        List<Object[]> batch = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            try {
                Map<String, Object> meta = MAPPER.readValue(String.valueOf(row.get("metadata")), Map.class);
                Object model = meta.get("embedding_model");
                if (model != null && !embeddingModel.equals(String.valueOf(model))) {
                    throw new IllegalStateException("embedding model changed");
                }
                Integer chunkIndex = meta.get("chunk_index") instanceof Number n
                        ? n.intValue() : Integer.valueOf(String.valueOf(meta.get("chunk_index")));
                Long newChunkId = chunkIdByIndex.get(chunkIndex);
                if (newChunkId == null) continue;
                Map<String, Object> merged = new LinkedHashMap<>(meta);
                merged.put("document_id", toDocumentId);
                merged.put("chunk_id", newChunkId);
                if (metadataOverrides != null) merged.putAll(metadataOverrides);
                Object effectiveFrom = metadataOverrides != null
                        && metadataOverrides.get("effective_from") != null
                        ? metadataOverrides.get("effective_from") : row.get("effective_from");
                Object effectiveTo = metadataOverrides != null
                        && metadataOverrides.containsKey("effective_to")
                        ? metadataOverrides.get("effective_to") : row.get("effective_to");
                batch.add(new Object[]{row.get("content"), row.get("embedding"),
                        MAPPER.writeValueAsString(merged), effectiveFrom, effectiveTo});
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                log.warn("复制向量行失败 doc={} err={}", toDocumentId, e.getMessage());
            }
        }
        if (batch.isEmpty()) return 0;
        for (int start = 0; start < batch.size(); start += 500) {
            pgJdbcTemplate.batchUpdate(sql,
                    batch.subList(start, Math.min(batch.size(), start + 500)));
        }
        return batch.size();
    }

    /** 某文档实际写入的向量行数，用于 MySQL/PgVector 跨库写入后的数量校验。 */
    public long countByDocument(Long tenantId, Long documentId, int dimensions) {
        String table = "%s.%s".formatted(schema(tenantId), table(dimensions));
        Long count = pgJdbcTemplate.queryForObject(
                "SELECT count(*) FROM %s WHERE metadata->>'document_id' = ?".formatted(table),
                Long.class, String.valueOf(documentId));
        return count == null ? 0 : count;
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

    private long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return 0;
    }

    String table(int dimensions) {
        // 兼容历史默认 768 维表名 embeddings；1024 维始终使用 embeddings_1024，
        // 避免默认模型从 nomic(768) 切到 bge-m3(1024) 后把旧表当成新维度表。
        return dimensions == 768 ? "embeddings" : "embeddings_" + dimensions;
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
