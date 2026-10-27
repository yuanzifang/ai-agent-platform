package com.platform.knowledge.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorRepositoryTest {

    private EmbeddingProperties properties() {
        EmbeddingProperties props = new EmbeddingProperties();
        props.setModel("nomic-embed-text");
        props.setDimensions(768);
        props.setAvailable(Map.of("nomic-embed-text", 768, "bge-m3", 1024));
        return props;
    }

    @Test
    void usesSeparateTablePerDimension() {
        VectorRepository repo = new VectorRepository(mock(JdbcTemplate.class), properties());
        assertEquals("embeddings", repo.table(768));
        assertEquals("embeddings_1024", repo.table(1024));
    }

    @Test
    void bgeDefaultKeepsLegacyEmbeddingsTableForNomic() {
        EmbeddingProperties props = new EmbeddingProperties();
        props.setModel("bge-m3");
        props.setDimensions(1024);
        props.setAvailable(Map.of("nomic-embed-text", 768, "bge-m3", 1024));
        VectorRepository repo = new VectorRepository(mock(JdbcTemplate.class), props);
        assertEquals("embeddings_1024", repo.table(1024));
        assertEquals("embeddings", repo.table(768), "768 维 nomic 继续使用历史表名");
    }

    @Test
    void upgradeAddsAndBackfillsVectorTimestamps() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        VectorRepository repo = new VectorRepository(jdbc, properties());

        repo.ensureTenantSchema(1L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).execute(sql.capture());
        String all = String.join("\n", sql.getAllValues());
        assertTrue(all.contains("updated_at"), "建表/升级应包含 updated_at");
        assertTrue(all.contains("ADD COLUMN IF NOT EXISTS created_at"), "旧表应补 created_at");
        assertTrue(all.contains("ADD COLUMN IF NOT EXISTS updated_at"), "旧表应补 updated_at");
        assertTrue(all.contains("SET metadata ="), "旧数据应回填 metadata 时间");
        assertTrue(all.contains("idx_embeddings_effective"), "应按生效时间建索引");
        assertTrue(all.contains("USING hnsw"), "应为向量列建 HNSW 索引");
    }

    @Test
    void updateValiditySyncsAllVectorTables() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        VectorRepository repo = new VectorRepository(jdbc, properties());
        java.sql.Timestamp from = java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        java.sql.Timestamp to = java.sql.Timestamp.from(Instant.parse("2026-02-01T00:00:00Z"));

        repo.updateValidityForDocument(1L, 2L, 3L, from, to);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), any(), any(), any(), any(), any(), any());
        assertTrue(sql.getAllValues().stream().allMatch(value ->
                value.contains("effective_from = ?") && value.contains("effective_to = ?")
                        && value.contains("updated_at = now()")));
    }

    @Test
    void saveBatchWritesCreatedAndUpdatedTimestamps() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        VectorRepository repo = new VectorRepository(jdbc, properties());
        Map<String, Object> row = Map.of(
                "content", "hello",
                "embedding", new float[]{0.1f, 0.2f},
                "metadata", "{}");

        repo.saveBatch(1L, List.of(row), 768);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).batchUpdate(sql.capture(), any(List.class));
        assertTrue(sql.getValue().contains("created_at"));
        assertTrue(sql.getValue().contains("updated_at"));
        assertTrue(sql.getValue().contains("effective_from"));
        assertTrue(sql.getValue().contains("effective_to"));
        assertTrue(sql.getValue().contains("jsonb_set"));
    }

    @Test
    void usesExactMaterializedScanForLowSelectivityFilter() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        VectorRepository repo = new VectorRepository(jdbc, properties());
        ReflectionTestUtils.setField(repo, "partialHnswEnabled", false);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1_000_000L);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(10_000L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        repo.search(1L, new float[]{0.1f, 0.2f}, 2L, 5, 0.5, 768, null, true);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("MATERIALIZED"), sql.getValue());
        assertTrue(sql.getValue().contains("ORDER BY score DESC"), sql.getValue());
        assertTrue(sql.getValue().contains("metadata->>'knowledge_base_id' = '2'"), sql.getValue());
    }

    @Test
    void reconcilesPartialHnswIndexForLargeKnowledgeBase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EmbeddingProperties props = new EmbeddingProperties();
        props.setModel("nomic-embed-text");
        props.setDimensions(768);
        props.setAvailable(Map.of("nomic-embed-text", 768));
        VectorRepository repo = new VectorRepository(jdbc, props);
        ReflectionTestUtils.setField(repo, "partialHnswEnabled", true);
        ReflectionTestUtils.setField(repo, "partialHnswMinRows", 10_000L);
        ReflectionTestUtils.setField(repo, "partialHnswMaxIndexes", 20);
        ReflectionTestUtils.setField(repo, "hnswConcurrently", true);

        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("kb_id", "42", "cnt", 75_000L)));
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of());

        var stats = repo.reconcilePartialIndexes(1L);

        assertEquals(1, stats.get("created"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).execute(sql.capture());
        assertTrue(sql.getValue().contains(
                "CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_embeddings_hnsw_kb_42"),
                sql.getValue());
        assertTrue(sql.getValue().contains(
                "WHERE metadata->>'knowledge_base_id' = '42'"), sql.getValue());
    }

    @Test
    void searchFiltersByTimestampBeforeLimit() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        VectorRepository repo = new VectorRepository(jdbc, properties());
        Instant after = Instant.parse("2026-01-01T00:00:00Z");
        Instant before = Instant.parse("2026-02-01T00:00:00Z");

        repo.search(1L, new float[]{0.1f, 0.2f}, 2L, 5, 0.5, 768,
                null, true, after, before);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("effective_to >= CAST(? AS timestamptz)"));
        assertTrue(sql.getValue().contains("effective_from <= CAST(? AS timestamptz)"));
        assertTrue(sql.getValue().contains("to_char(effective_from"));
        assertTrue(sql.getValue().contains("to_char(effective_to"));
    }
}
