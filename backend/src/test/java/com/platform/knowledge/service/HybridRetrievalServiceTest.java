package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.exception.BusinessException;
import com.platform.knowledge.entity.KnowledgeChunk;
import com.platform.knowledge.entity.KnowledgeParent;
import com.platform.knowledge.mapper.KnowledgeBaseMapper;
import com.platform.knowledge.mapper.KnowledgeChunkMapper;
import com.platform.knowledge.mapper.KnowledgeParentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridRetrievalServiceTest {

    private EmbeddingService embedding;
    private VectorRepository vectorRepository;
    private KnowledgeChunkMapper chunkMapper;
    private KnowledgeBaseMapper kbMapper;
    private HybridRetrievalService service;

    @BeforeEach
    void setUp() {
        embedding = mock(EmbeddingService.class);
        vectorRepository = mock(VectorRepository.class);
        chunkMapper = mock(KnowledgeChunkMapper.class);
        kbMapper = mock(KnowledgeBaseMapper.class);
        when(embedding.model()).thenReturn("nomic-embed-text");
        when(embedding.dimension(anyString())).thenReturn(768);
        service = new HybridRetrievalService(embedding, vectorRepository, chunkMapper, kbMapper,
                new ObjectMapper());
        ReflectionTestUtils.setField(service, "hybridEnabled", true);
        ReflectionTestUtils.setField(service, "defaultKeywordWeight", 0.35);
    }

    @Test
    void hybridMergesByChunkIdWithRrf() {
        when(embedding.embedOne(eq("滤芯"), anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(vectorRepository.search(anyLong(), any(float[].class), anyLong(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(List.of(Map.of("content", "A", "score", 0.9,
                        "metadata", "{\"chunk_id\":1,\"document_id\":1}")));
        when(chunkMapper.searchFulltext(eq(1L), anyString(), anyInt())).thenReturn(List.of(
                Map.of("id", 2, "content", "B", "score", 5, "document_id", 1, "chunk_index", 0),
                Map.of("id", 1, "content", "A2", "score", 3, "document_id", 1, "chunk_index", 0)));

        var result = service.search(1L, "滤芯", 1L, 5, 0.5, Map.of());

        assertEquals("hybrid", result.mode());
        assertEquals(2, result.hits().size());
        assertEquals(1, result.vectorHits());
        assertEquals(2, result.keywordHits());
        assertEquals("A", result.hits().get(0).get("content"));
        assertEquals(1.0, (double) result.hits().get(0).get("score"), 0.0001);
    }

    @Test
    void vectorModeSkipsKeyword() {
        when(embedding.embedOne(eq("q"), anyString())).thenReturn(new float[]{0.1f});
        when(vectorRepository.search(anyLong(), any(float[].class), anyLong(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(List.of(Map.of("content", "A", "score", 0.8,
                        "metadata", "{\"chunk_id\":1}")));

        var result = service.search(1L, "q", 1L, 5, 0.5, Map.of("mode", "vector"));

        assertEquals("vector", result.mode());
        assertEquals(1, result.hits().size());
        verify(chunkMapper, never()).searchFulltext(anyLong(), anyString(), anyInt());
    }

    @Test
    void relevanceScoreBreaksTieWithoutChangingScores() {
        when(embedding.embedOne(eq("q"), anyString())).thenReturn(new float[]{0.1f});
        when(vectorRepository.search(anyLong(), any(float[].class), anyLong(), anyInt(),
                anyDouble(), anyInt())).thenReturn(List.of(
                Map.of("content", "low", "score", 0.9,
                        "metadata", "{\"chunk_id\":1,\"relevance_score\":0.2}"),
                Map.of("content", "high", "score", 0.9,
                        "metadata", "{\"chunk_id\":2,\"relevance_score\":0.95}")));

        var result = service.search(1L, "q", 1L, 5, 0.5, Map.of("mode", "vector"));

        assertEquals("high", result.hits().get(0).get("content"));
        assertEquals(0.9, (double) result.hits().get(0).get("score"), 0.0001);
    }

    @Test
    void filtersVectorHitsByChunkMetadata() {
        when(embedding.embedOne(eq("q"), anyString())).thenReturn(new float[]{0.1f});
        when(vectorRepository.search(anyLong(), any(float[].class), anyLong(), anyInt(),
                anyDouble(), anyInt())).thenReturn(List.of(
                Map.of("content", "research", "score", 0.9,
                        "metadata", "{\"chunk_id\":1,\"document_type\":\"research_paper\"}"),
                Map.of("content", "regulatory", "score", 0.8,
                        "metadata", "{\"chunk_id\":2,\"document_type\":\"regulatory\",\"relevance_score\":0.9}")));

        var result = service.search(1L, "q", 1L, 5, 0.5,
                Map.of("mode", "vector", "documentType", "regulatory"));

        assertEquals(1, result.hits().size());
        assertEquals("regulatory", result.hits().get(0).get("content"));
    }

    @Test
    void hybridFallsBackToKeywordWhenEmbeddingFails() {
        when(embedding.embedOne(eq("q"), anyString())).thenThrow(new BusinessException(502, "ollama down"));
        when(chunkMapper.searchFulltext(eq(1L), anyString(), anyInt())).thenReturn(List.of(
                Map.of("id", 9, "content", "关键字命中", "score", 4, "document_id", 1, "chunk_index", 0)));

        var result = service.search(1L, "q", 1L, 5, 0.5, Map.of());

        assertEquals("keyword", result.mode());
        assertEquals(1, result.hits().size());
        assertTrue(String.valueOf(result.hits().get(0).get("content")).contains("关键字命中"));
    }

    @Test
    void blankQueryShortCircuitsWithoutCallingRetrievalBackends() {
        var result = service.search(1L, "   ", 1L, 5, 0.5, Map.of());

        assertEquals(0, result.hits().size());
        verifyNoInteractions(embedding, vectorRepository, chunkMapper);
    }

    @Test
    void aclOverloadPushesUserFilterToVectorAndFulltext() {
        when(embedding.embedOne(eq("q"), anyString())).thenReturn(new float[]{0.1f});
        when(vectorRepository.search(anyLong(), any(float[].class), anyLong(), anyInt(),
                anyDouble(), anyInt(), eq(7L), eq(false)))
                .thenReturn(List.of(Map.of("content", "向量命中", "score", 0.9,
                        "metadata", "{\"chunk_id\":1,\"document_id\":1}")));
        when(chunkMapper.searchFulltextAcl(eq(1L), anyString(), anyInt(), eq(7L), eq(false)))
                .thenReturn(List.of(Map.of("id", 2, "content", "全文命中", "score", 4,
                        "document_id", 1, "chunk_index", 0)));

        var result = service.search(9L, "q", 1L, 5, 0.5,
                Map.of("mode", "hybrid"), 7L, false);

        assertEquals(2, result.hits().size());
        assertEquals(1, result.vectorHits());
        assertEquals(1, result.keywordHits());
        verify(vectorRepository).search(eq(9L), any(float[].class), eq(1L), anyInt(),
                eq(0.5), anyInt(), eq(7L), eq(false));
        verify(chunkMapper).searchFulltextAcl(eq(1L), anyString(), anyInt(), eq(7L), eq(false));
    }

    @Test
    void validityFilterPushesToVectorAndKeywordSql() {
        Instant after = Instant.parse("2026-01-01T00:00:00Z");
        Instant before = Instant.parse("2026-02-01T00:00:00Z");
        LocalDateTime localAfter = LocalDateTime.ofInstant(after, ZoneId.systemDefault());
        LocalDateTime localBefore = LocalDateTime.ofInstant(before, ZoneId.systemDefault());
        when(embedding.embedOne(eq("q"), anyString())).thenReturn(new float[]{0.1f});
        when(vectorRepository.search(eq(1L), any(float[].class), eq(1L), anyInt(), anyDouble(), anyInt(),
                isNull(), eq(true), eq(after), eq(before)))
                .thenReturn(List.of(Map.of("content", "向量命中", "score", 0.9,
                        "metadata", "{\"chunk_id\":1,\"document_id\":1}")));
        when(chunkMapper.searchFulltextByValidity(eq(1L), eq("q"), anyInt(),
                eq(localAfter), eq(localBefore), isNull(), eq(true)))
                .thenReturn(List.of(Map.of("id", 2, "content", "关键字命中", "score", 4,
                        "document_id", 1, "chunk_index", 0, "effective_from", "2026-01-01T00:00:00")));

        var result = service.search(1L, "q", 1L, 5, 0.5, Map.of(), null, true, after, before);

        assertEquals(2, result.hits().size());
        assertEquals(1, result.vectorHits());
        assertEquals(1, result.keywordHits());
        verify(vectorRepository).search(eq(1L), any(float[].class), eq(1L), anyInt(), anyDouble(), anyInt(),
                isNull(), eq(true), eq(after), eq(before));
        verify(chunkMapper).searchFulltextByValidity(eq(1L), eq("q"), anyInt(),
                eq(localAfter), eq(localBefore), isNull(), eq(true));
    }

    @Test
    void vectorOnlyModeThrows502WhenEmbeddingFails() {
        when(embedding.embedOne(eq("q"), anyString()))
                .thenThrow(new BusinessException(502, "embedding down"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.search(1L, "q", 1L, 5, 0.5, Map.of("mode", "vector")));

        assertEquals(502, error.getCode());
        verify(chunkMapper, never()).searchFulltext(anyLong(), anyString(), anyInt());
    }

    @Test
    void fulltextFailureFallsBackToLikeSearch() {
        when(embedding.embedOne(eq("q"), anyString())).thenReturn(new float[]{0.1f});
        when(vectorRepository.search(anyLong(), any(float[].class), anyLong(), anyInt(),
                anyDouble(), anyInt())).thenReturn(List.of());
        when(chunkMapper.searchFulltext(eq(1L), anyString(), anyInt()))
                .thenThrow(new RuntimeException("ngram index unavailable"));
        when(chunkMapper.searchLike(eq(1L), anyString(), anyInt())).thenReturn(List.of(
                Map.of("id", 9, "content", "LIKE 兜底", "score", 0.1,
                        "document_id", 1, "chunk_index", 0)));

        var result = service.search(1L, "q", 1L, 5, 0.5, Map.of());

        assertEquals(1, result.keywordHits());
        assertTrue(String.valueOf(result.hits().get(0).get("content")).contains("LIKE 兜底"));
        verify(chunkMapper).searchLike(eq(1L), anyString(), anyInt());
    }

    @Test
    void nullRetrievalConfigUsesDefaults() {
        when(embedding.embedOne(eq("q"), anyString())).thenReturn(new float[]{0.1f});
        when(vectorRepository.search(anyLong(), any(float[].class), anyLong(), anyInt(),
                anyDouble(), anyInt())).thenReturn(List.of());

        var result = service.search(1L, "q", 1L, 5, 0.5, null);

        assertEquals("hybrid", result.mode());
    }

    @Test
    void invalidModeFallsBackToConfiguredModeWhenHybridDisabled() {
        ReflectionTestUtils.setField(service, "hybridEnabled", false);
        when(embedding.embedOne(eq("q"), anyString())).thenReturn(new float[]{0.1f});
        when(vectorRepository.search(anyLong(), any(float[].class), anyLong(), anyInt(),
                anyDouble(), anyInt())).thenReturn(List.of());

        var result = service.search(1L, "q", 1L, 5, 0.5, Map.of("mode", "bogus"));

        assertEquals("vector", result.mode());
        verify(chunkMapper, never()).searchFulltext(anyLong(), anyString(), anyInt());
    }

    @Test
    void dedupesHitsFromSameParent() {
        when(embedding.embedOne(eq("q"), anyString())).thenReturn(new float[]{0.1f});
        when(vectorRepository.search(anyLong(), any(float[].class), anyLong(), anyInt(),
                anyDouble(), anyInt())).thenReturn(List.of(
                Map.of("content", "子块1", "score", 0.9,
                        "metadata", "{\"chunk_id\":11,\"document_id\":100,\"chunk_index\":1}"),
                Map.of("content", "子块2", "score", 0.8,
                        "metadata", "{\"chunk_id\":12,\"document_id\":100,\"chunk_index\":2}")));
        KnowledgeChunk c1 = chunk(11L, 100L, 1, "子块1");
        c1.setParentContent("同一父块");
        c1.setParentIndex(7);
        KnowledgeChunk c2 = chunk(12L, 100L, 2, "子块2");
        c2.setParentContent("同一父块");
        c2.setParentIndex(7);
        when(chunkMapper.selectBatchIds(anyCollection())).thenReturn(List.of(c1, c2));

        var result = service.search(1L, "q", 1L, 5, 0.5, Map.of("mode", "vector"));

        assertEquals(1, result.hits().size(), "同一父块的多个命中应合并");
        assertEquals("同一父块", result.hits().get(0).get("parentContent"));
        assertEquals(0.9, (double) result.hits().get(0).get("score"), 0.0001);
        assertTrue(result.hits().get(0).get("mergedScores") instanceof List);
    }

    @Test
    void loadsParentContentFromParentTable() {
        KnowledgeParentMapper parentMapper = mock(KnowledgeParentMapper.class);
        ReflectionTestUtils.setField(service, "parentMapper", parentMapper);
        when(embedding.embedOne(eq("q"), anyString())).thenReturn(new float[]{0.1f});
        when(vectorRepository.search(anyLong(), any(float[].class), anyLong(), anyInt(),
                anyDouble(), anyInt())).thenReturn(List.of(
                Map.of("content", "子块", "score", 0.9,
                        "metadata", "{\"chunk_id\":11,\"document_id\":100,\"chunk_index\":1,\"parent_index\":7}")));
        KnowledgeChunk stored = chunk(11L, 100L, 1, "子块");
        stored.setParentIndex(7);
        when(chunkMapper.selectBatchIds(anyCollection())).thenReturn(List.of(stored));
        KnowledgeParent parent = new KnowledgeParent();
        parent.setDocumentId(100L);
        parent.setParentIndex(7);
        parent.setContent("父块表内容");
        when(parentMapper.selectList(any())).thenReturn(List.of(parent));

        var result = service.search(1L, "q", 1L, 5, 0.5, Map.of("mode", "vector"));

        assertEquals("父块表内容", result.hits().get(0).get("parentContent"));
        assertEquals(7, result.hits().get(0).get("parentIndex"));
        verify(chunkMapper, never()).selectList(any());
    }

    @Test
    void parentContentExpansionUsesBatchQueries() {
        when(embedding.embedOne(eq("q"), anyString())).thenReturn(new float[]{0.1f});
        when(vectorRepository.search(anyLong(), any(float[].class), anyLong(), anyInt(),
                anyDouble(), anyInt())).thenReturn(List.of(
                Map.of("content", "命中1", "score", 0.9,
                        "metadata", "{\"chunk_id\":11,\"document_id\":100,\"chunk_index\":1}"),
                Map.of("content", "命中2", "score", 0.8,
                        "metadata", "{\"chunk_id\":12,\"document_id\":100,\"chunk_index\":2}")));
        KnowledgeChunk stored = chunk(11L, 100L, 1, "命中1");
        stored.setParentContent("入库父块");
        stored.setParentIndex(7);
        KnowledgeChunk c0 = chunk(20L, 100L, 0, "前一段");
        KnowledgeChunk c1 = chunk(21L, 100L, 1, "命中1");
        KnowledgeChunk c2 = chunk(22L, 100L, 2, "命中2");
        when(chunkMapper.selectBatchIds(anyCollection())).thenReturn(List.of(stored));
        when(chunkMapper.selectList(any())).thenReturn(List.of(c0, c1, c2));

        var result = service.search(1L, "q", 1L, 5, 0.5, Map.of("mode", "vector"));

        assertEquals(2, result.hits().size());
        assertEquals("入库父块", result.hits().get(0).get("parentContent"));
        assertEquals(7, result.hits().get(0).get("parentIndex"));
        assertEquals("命中1\n命中2", result.hits().get(1).get("parentContent"));
        verify(chunkMapper, times(1)).selectBatchIds(anyCollection());
        verify(chunkMapper, times(1)).selectList(any());
        verify(chunkMapper, never()).selectById(any());
    }

    private KnowledgeChunk chunk(Long id, Long docId, int index, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setDocumentId(docId);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        return chunk;
    }

    @Test
    void rerankCanBeDisabledPerRequest() {
        RerankService rerank = mock(RerankService.class);
        when(rerank.enabled()).thenReturn(true);
        ReflectionTestUtils.setField(service, "rerankService", rerank);
        when(chunkMapper.searchFulltext(eq(1L), anyString(), anyInt())).thenReturn(List.of(
                Map.of("id", 1, "content", "A", "score", 1,
                        "document_id", 1, "chunk_index", 0)));

        service.search(1L, "q", 1L, 5, 0.5,
                Map.of("mode", "keyword", "rerank", "false"));

        verify(rerank, never()).rerank(anyString(), anyList(), anyInt());
    }

}
