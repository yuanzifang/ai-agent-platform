package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.exception.BusinessException;
import com.platform.knowledge.mapper.KnowledgeBaseMapper;
import com.platform.knowledge.mapper.KnowledgeChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
