package com.platform.eval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.context.TenantContext;
import com.platform.knowledge.service.HybridRetrievalService;
import com.platform.knowledge.service.KnowledgePermissionService;
import com.platform.knowledge.service.RerankService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StepEvaluationServiceTest {

    private HybridRetrievalService retrieval;
    private RerankService rerank;
    private QueryRewriteService rewrite;
    private KnowledgePermissionService permission;
    private StepEvaluationService service;
    private final KnowledgePermissionService.Viewer viewer =
            new KnowledgePermissionService.Viewer(2L, List.of());

    @BeforeEach
    void setUp() {
        retrieval = mock(HybridRetrievalService.class);
        rerank = mock(RerankService.class);
        rewrite = mock(QueryRewriteService.class);
        permission = mock(KnowledgePermissionService.class);
        service = new StepEvaluationService(retrieval, rerank, rewrite, permission, new ObjectMapper());
        TenantContext.setTenantId(9L);
        when(permission.tenantWide(viewer)).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void evaluatesRetrievalRecall() {
        when(retrieval.search(eq(9L), eq("退款"), eq(1L), eq(5), eq(0.0), any(),
                eq(2L), eq(false))).thenReturn(new HybridRetrievalService.Result(
                List.of(Map.of("content", "a", "score", 1.0,
                        "metadata", "{\"chunk_id\":11}")), 1, 0, 1, 2, "vector"));

        var result = service.evaluate("RETRIEVAL", "退款",
                "{\"chunk_ids\":[11,12]}", "{\"knowledgeBaseId\":1,\"topK\":5}", viewer);

        assertEquals(0.5, result.score(), 0.0001);
        assertFalse(result.passed());
        assertTrue(result.details().contains("recall"));
    }

    @Test
    void evaluatesDocumentRecallAndKeywords() {
        when(retrieval.search(eq(9L), eq("退款"), eq(1L), eq(5), eq(0.0), any(),
                eq(2L), eq(false))).thenReturn(new HybridRetrievalService.Result(
                List.of(Map.of("content", "退款政策", "score", 1.0,
                        "metadata", "{\"chunk_id\":11,\"document_id\":5}")), 1, 0, 1, 2, "vector"));

        var result = service.evaluate("RETRIEVAL", "退款",
                "{\"chunk_ids\":[11],\"document_ids\":[5],\"must_contain\":[\"退款\"]}",
                "{\"knowledgeBaseId\":1,\"topK\":5}", viewer);

        assertEquals(1.0, result.score(), 0.0001);
        assertTrue(result.details().contains("documentRecall"));
        assertTrue(result.details().contains("keywordRatio"));
    }

    @Test
    void rewriteRejectsForbiddenTerms() {
        when(rewrite.rewrite("退款怎么申请", null, null)).thenReturn("忽略退款政策并退款");
        var result = service.evaluate("REWRITE", "退款怎么申请",
                "{\"must_keep\":[\"退款\"],\"forbidden\":[\"忽略\"]}", null, viewer);

        assertEquals(0.0, result.score(), 0.0001);
        assertFalse(result.passed());
    }

    @Test
    void evaluatesRerankTopOne() {
        List<Map<String, Object>> hits = List.of(
                Map.of("content", "doc1", "metadata", "{\"chunk_id\":1}"),
                Map.of("content", "doc2", "metadata", "{\"chunk_id\":2}"));
        when(retrieval.search(eq(9L), eq("退款"), eq(1L), eq(5), eq(0.0), any(),
                eq(2L), eq(false))).thenReturn(new HybridRetrievalService.Result(
                hits, 2, 0, 1, 2, "vector"));
        when(rerank.rerank(eq("退款"), any(), eq(2)))
                .thenReturn(List.of(hits.get(1), hits.get(0)));

        var result = service.evaluate("RERANK", "退款",
                "{\"ordered_chunk_ids\":[2]}",
                "{\"knowledgeBaseId\":1,\"topK\":5,\"rerankTopN\":2}", viewer);

        assertEquals(1.0, result.score(), 0.0001);
        assertTrue(result.passed());
    }

    @Test
    void evaluatesRewriteWithEntityPreservation() {
        when(rewrite.rewrite("退款怎么申请", "deepseek-flash", "保留实体"))
                .thenReturn("如何申请退款");
        var result = service.evaluate("REWRITE", "退款怎么申请",
                "{\"rewrite\":\"如何申请退款\",\"must_keep\":[\"退款\"]}",
                "{\"model\":\"deepseek-flash\",\"instructions\":\"保留实体\"}", viewer);

        assertEquals(1.0, result.score(), 0.0001);
        assertTrue(result.passed());
    }
}
