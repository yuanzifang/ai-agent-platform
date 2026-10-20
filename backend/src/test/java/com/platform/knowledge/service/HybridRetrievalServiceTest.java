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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
}
