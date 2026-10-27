package com.platform.knowledge.service;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量库租户隔离回归测试（可选）：-Dpgvector.it=true 时执行。
 * 用两个真实租户 Schema 钉住 schema 边界：跨租户写入互不可见、删除不越界。
 * 判别设计：检索阈值 0.1——若 schema 边界被穿破，租户A 的行 score≈1 必现；
 * 租户B 自有正交行 score=0 不会命中。
 * 前置：本机 PG 已含 pgvector 扩展（PG_URL/PG_USERNAME/PG_PASSWORD 可覆盖；
 * 本机默认跟随系统用户名——Homebrew PG 惯例 trust 认证）。
 */
@EnabledIfSystemProperty(named = "pgvector.it", matches = "true")
class VectorIsolationIT {

    private static final long TENANT_A = 900_001L;
    private static final long TENANT_B = 900_002L;
    private static final int DIM = 4;

    private static HikariDataSource ds;
    private static JdbcTemplate jdbc;
    private static VectorRepository repo;

    @BeforeAll
    static void setUp() {
        ds = new HikariDataSource();
        ds.setJdbcUrl(System.getenv().getOrDefault("PG_URL", "jdbc:postgresql://127.0.0.1:5432/vector"));
        // 本机默认跟随系统用户名（Homebrew PG 惯例，trust 认证）；CI/部署经 PG_USERNAME/PG_PASSWORD 覆盖
        ds.setUsername(System.getenv().getOrDefault("PG_USERNAME", System.getProperty("user.name")));
        ds.setPassword(System.getenv().getOrDefault("PG_PASSWORD", ""));
        ds.setMaximumPoolSize(2);
        jdbc = new JdbcTemplate(ds);
        try {
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "pgvector 扩展不可用：" + e.getMessage());
        }
        EmbeddingProperties props = new EmbeddingProperties();
        props.setModel("it-model");
        props.setDimensions(DIM);
        props.setAvailable(Map.of("it-model", DIM));
        repo = new VectorRepository(jdbc, props);
        repo.ensureTenantSchema(TENANT_A);
        repo.ensureTenantSchema(TENANT_B);
    }

    @AfterAll
    static void tearDown() {
        if (jdbc != null) {
            jdbc.execute("DROP SCHEMA IF EXISTS tenant_" + TENANT_A + " CASCADE");
            jdbc.execute("DROP SCHEMA IF EXISTS tenant_" + TENANT_B + " CASCADE");
        }
        if (ds != null) ds.close();
    }

    private static Map<String, Object> row(String content, float[] vector, long kb, long chunk) {
        // metadata 与生产链路一致：JSON 字符串（JDBC 侧 ?::jsonb 不接受 Java Map）
        String metadata = "{\"knowledge_base_id\":\"" + kb + "\",\"chunk_id\":\"" + chunk
                + "\",\"chunk_index\":0}";
        return Map.of("content", content, "embedding", vector, "metadata", metadata);
    }

    private static Map<String, Object> rowAcl(String content, float[] vector, long kb, long chunk,
                                              String allowedUsers, boolean tenantVisible) {
        String acl = allowedUsers == null ? "" : ",\"allowed_users\":" + allowedUsers;
        String metadata = "{\"knowledge_base_id\":\"" + kb + "\",\"chunk_id\":\"" + chunk
                + "\",\"chunk_index\":0" + acl + ",\"tenant_visible\":" + tenantVisible + "}";
        return Map.of("content", content, "embedding", vector, "metadata", metadata);
    }

    @Test
    void appliesChunkAclAndTenantVisibility() {
        repo.saveBatch(TENANT_A, List.of(
                rowAcl("user seven", new float[]{1, 0, 0, 0}, 806, 1, "[\"7\"]", false),
                rowAcl("user eight", new float[]{0.9f, 0.1f, 0, 0}, 806, 2, "[\"8\"]", false),
                rowAcl("tenant visible", new float[]{0.8f, 0.2f, 0, 0}, 806, 3, "[]", true)));

        var user7 = repo.search(TENANT_A, new float[]{1, 0, 0, 0}, 806L, 5, 0.1,
                DIM, 7L, false);
        var other = repo.search(TENANT_A, new float[]{1, 0, 0, 0}, 806L, 5, 0.1,
                DIM, 9L, false);
        var tenant7 = repo.search(TENANT_A, new float[]{1, 0, 0, 0}, 806L, 5, 0.1,
                DIM, 7L, true);

        assertEquals(1, user7.size(), "用户 7 只能看到自己的 chunk");
        assertEquals(0, other.size(), "其他用户不能看到私有 chunk");
        assertEquals(2, tenant7.size(), "租户可见时应包含自己的 chunk 和租户可见 chunk");
    }

    @Test
    void crossTenantWritesAreInvisibleToEachOther() {
        repo.saveBatch(TENANT_A, List.of(row("租户A机密内容", new float[]{1, 0, 0, 0}, 801, 1)));
        repo.saveBatch(TENANT_B, List.of(row("租户B机密内容", new float[]{0, 1, 0, 0}, 801, 1)));

        // 同一查询向量：租户A 命中自己的行（score≈1）；租户B 的行正交（score=0 < 0.1）不命中——
        // 若 schema 边界被穿破，租户B 会看到租户A 的行（score≈1）导致断言失败
        var hitsA = repo.search(TENANT_A, new float[]{1, 0, 0, 0}, 801L, 10, 0.1, DIM);
        var hitsB = repo.search(TENANT_B, new float[]{1, 0, 0, 0}, 801L, 10, 0.1, DIM);

        assertEquals(1, hitsA.size(), "租户A 库内应能检索到自己");
        assertTrue(String.valueOf(hitsA.get(0).get("content")).contains("租户A"), "内容归属校验");
        assertEquals(0, hitsB.size(), "租户B 不得检索到租户A 的向量（schema 边界）");
    }

    @Test
    void createsAndUsesPartialHnswIndexForKnowledgeBase() {
        ReflectionTestUtils.setField(repo, "partialHnswMinRows", 2L);
        ReflectionTestUtils.setField(repo, "partialHnswMaxIndexes", 10);
        ReflectionTestUtils.setField(repo, "partialHnswMaxRatio", 1.0);
        ReflectionTestUtils.setField(repo, "hnswConcurrently", false);
        repo.saveBatch(TENANT_A, List.of(
                row("partial one", new float[]{1, 0, 0, 0}, 805, 1),
                row("partial two", new float[]{0.9f, 0.1f, 0, 0}, 805, 2)));

        var stats = repo.reconcilePartialIndexes(TENANT_A);

        assertTrue(stats.get("created") >= 1, "应至少创建当前知识库的 partial HNSW");
        Integer indexes = jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = ? AND indexname = ?
                """, Integer.class, "tenant_" + TENANT_A,
                "idx_embeddings_4_hnsw_kb_805");
        assertEquals(1, indexes, "partial HNSW 索引应存在");

        var hits = repo.search(TENANT_A, new float[]{1, 0, 0, 0}, 805L, 5, 0.1, DIM);
        assertEquals(2, hits.size());
    }

    @Test
    void skipsPartialIndexWhenKnowledgeBaseRatioExceedsLimit() {
        ReflectionTestUtils.setField(repo, "partialHnswMinRows", 2L);
        ReflectionTestUtils.setField(repo, "partialHnswMaxIndexes", 10);
        ReflectionTestUtils.setField(repo, "partialHnswMaxRatio", 0.01);
        ReflectionTestUtils.setField(repo, "partialHnswPrune", false);
        ReflectionTestUtils.setField(repo, "hnswConcurrently", false);
        repo.saveBatch(TENANT_A, List.of(
                row("ratio one", new float[]{1, 0, 0, 0}, 807, 1),
                row("ratio two", new float[]{0, 1, 0, 0}, 807, 2)));

        repo.reconcilePartialIndexes(TENANT_A);
        assertEquals(0, indexCount("idx_embeddings_4_hnsw_kb_807"), "高占比知识库不应建 partial 索引");

        ReflectionTestUtils.setField(repo, "partialHnswMaxRatio", 1.0);
        repo.reconcilePartialIndexes(TENANT_A);
        assertEquals(1, indexCount("idx_embeddings_4_hnsw_kb_807"), "降低占比约束后应建索引");
    }

    @Test
    void prunesPartialIndexAfterKnowledgeBaseDisappears() {
        ReflectionTestUtils.setField(repo, "partialHnswMinRows", 2L);
        ReflectionTestUtils.setField(repo, "partialHnswMaxIndexes", 10);
        ReflectionTestUtils.setField(repo, "partialHnswMaxRatio", 1.0);
        ReflectionTestUtils.setField(repo, "partialHnswPrune", true);
        ReflectionTestUtils.setField(repo, "hnswConcurrently", false);
        repo.saveBatch(TENANT_A, List.of(
                row("prune one", new float[]{1, 0, 0, 0}, 804, 1),
                row("prune two", new float[]{0, 1, 0, 0}, 804, 2)));
        repo.reconcilePartialIndexes(TENANT_A);
        assertEquals(1, indexCount("idx_embeddings_4_hnsw_kb_804"), "索引应先创建");

        repo.deleteByKnowledgeBase(TENANT_A, 804L);
        var stats = repo.reconcilePartialIndexes(TENANT_A);

        assertTrue(stats.get("dropped") >= 1, "删除知识库后应 prune 索引");
        assertEquals(0, indexCount("idx_embeddings_4_hnsw_kb_804"), "索引应被删除");
    }

    private int indexCount(String indexName) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = ? AND indexname = ?
                """, Integer.class, "tenant_" + TENANT_A, indexName);
        return count == null ? 0 : count;
    }

    @Test
    void deleteByKnowledgeBaseDoesNotCrossTenants() {
        repo.saveBatch(TENANT_A, List.of(row("A删除前", new float[]{0, 0, 1, 0}, 802, 2)));
        repo.saveBatch(TENANT_B, List.of(row("B保留", new float[]{0, 0, 1, 0}, 802, 2)));

        repo.deleteByKnowledgeBase(TENANT_A, 802L);

        assertEquals(0, countKb(TENANT_A, 802), "租户A 的 802 应被清空");
        assertEquals(1, countKb(TENANT_B, 802), "租户B 的 802 不得被级联误删");
    }

    private int countKb(long tenantId, long kb) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM tenant_" + tenantId + ".embeddings_"
                        + DIM + " WHERE metadata->>'knowledge_base_id' = ?",
                Integer.class, String.valueOf(kb));
        return n == null ? 0 : n;
    }
}
