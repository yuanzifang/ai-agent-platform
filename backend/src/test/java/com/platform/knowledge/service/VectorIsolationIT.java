package com.platform.knowledge.service;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量库租户隔离回归测试（可选）：-Dpgvector.it=true 时执行。
 * 用两个真实租户 Schema 钉住 schema 边界：跨租户写入互不可见、删除不越界。
 * 前置：本机 PG 已含 pgvector 扩展（PG_URL/PG_USERNAME/PG_PASSWORD 可覆盖，默认 CI/本地一致）。
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

    @Test
    void crossTenantWritesAreInvisibleToEachOther() {
        repo.saveBatch(TENANT_A, List.of(row("租户A机密内容", new float[]{1, 0, 0, 0}, 801, 1)));
        repo.saveBatch(TENANT_B, List.of(row("租户B机密内容", new float[]{0, 1, 0, 0}, 801, 1)));

        var hitsA = repo.search(TENANT_A, new float[]{1, 0, 0, 0}, 801L, 10, 0.1, DIM);
        var hitsB = repo.search(TENANT_B, new float[]{1, 0, 0, 0}, 801L, 10, 0.1, DIM);

        assertEquals(1, hitsA.size(), "租户A 库内应能检索到自己");
        assertTrue(String.valueOf(hitsA.get(0).get("content")).contains("租户A"), "内容归属校验");
        assertEquals(0, hitsB.size(), "租户B 不得检索到租户A 的向量（schema 边界）");
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
