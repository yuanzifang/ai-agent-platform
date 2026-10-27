package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.mapper.KnowledgeBaseMapper;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按知识库的 vector_backend 路由到 pgvector 或 Qdrant。
 * 默认（字段为空/未知）继续走 pgvector。
 */
@Slf4j
@Service
@Primary
public class VectorStoreRouter implements VectorStore {

    private final VectorRepository pgvector;
    private final ObjectProvider<QdrantVectorStore> qdrantProvider;
    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final ObjectMapper objectMapper;

    public VectorStoreRouter(VectorRepository pgvector,
                             ObjectProvider<QdrantVectorStore> qdrantProvider,
                             KnowledgeBaseMapper kbMapper,
                             KnowledgeDocumentMapper documentMapper,
                             ObjectMapper objectMapper) {
        this.pgvector = pgvector;
        this.qdrantProvider = qdrantProvider;
        this.kbMapper = kbMapper;
        this.documentMapper = documentMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public String provider() {
        return "router";
    }

    @Override
    public void ensureTenantSchema(Long tenantId) {
        pgvector.ensureTenantSchema(tenantId);
        QdrantVectorStore qdrant = qdrant();
        if (qdrant != null) {
            try {
                qdrant.ensureTenantSchema(tenantId);
            } catch (Exception e) {
                log.warn("Qdrant schema 初始化跳过 tenant={} err={}", tenantId, e.getMessage());
            }
        }
    }

    @Override
    public void saveBatch(Long tenantId, List<Map<String, Object>> rows) {
        saveBatch(tenantId, rows, 1024);
    }

    @Override
    public void saveBatch(Long tenantId, List<Map<String, Object>> rows, int dimensions) {
        if (rows == null || rows.isEmpty()) return;
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String backend = backendOfMetadata(row.get("metadata"));
            grouped.computeIfAbsent(backend, k -> new ArrayList<>()).add(row);
        }
        for (var entry : grouped.entrySet()) {
            VectorStore store = "QDRANT".equals(entry.getKey()) ? qdrantOrPg() : pgvector;
            store.saveBatch(tenantId, entry.getValue(), dimensions);
        }
    }

    @Override
    public List<Map<String, Object>> search(Long tenantId, float[] queryVector, Long knowledgeBaseId,
                                            int topK, double threshold) {
        return forKb(knowledgeBaseId).search(tenantId, queryVector, knowledgeBaseId, topK, threshold);
    }

    @Override
    public List<Map<String, Object>> search(Long tenantId, float[] queryVector, Long knowledgeBaseId,
                                            int topK, double threshold, int dimensions) {
        return forKb(knowledgeBaseId).search(tenantId, queryVector, knowledgeBaseId, topK, threshold, dimensions);
    }

    @Override
    public List<Map<String, Object>> search(Long tenantId, float[] queryVector, Long knowledgeBaseId,
                                            int topK, double threshold, int dimensions,
                                            Long userId, boolean tenantWide) {
        return forKb(knowledgeBaseId).search(tenantId, queryVector, knowledgeBaseId, topK, threshold,
                dimensions, userId, tenantWide);
    }

    @Override
    public List<Map<String, Object>> search(Long tenantId, float[] queryVector, Long knowledgeBaseId,
                                            int topK, double threshold, int dimensions,
                                            Long userId, boolean tenantWide,
                                            Instant effectiveAfter, Instant effectiveBefore) {
        return forKb(knowledgeBaseId).search(tenantId, queryVector, knowledgeBaseId, topK, threshold,
                dimensions, userId, tenantWide, effectiveAfter, effectiveBefore);
    }

    @Override
    public void updateAclForKnowledgeBase(Long tenantId, Long knowledgeBaseId,
                                          List<Long> allowedUsers, boolean tenantWide) {
        forKb(knowledgeBaseId).updateAclForKnowledgeBase(tenantId, knowledgeBaseId, allowedUsers, tenantWide);
    }

    @Override
    public void updateAclForChunk(Long tenantId, Long knowledgeBaseId, Long chunkId,
                                  List<Long> allowedUsers, boolean tenantWide) {
        forKb(knowledgeBaseId).updateAclForChunk(tenantId, knowledgeBaseId, chunkId, allowedUsers, tenantWide);
    }

    @Override
    public void updateContentForChunk(Long tenantId, Long knowledgeBaseId, Long chunkId,
                                      String content, float[] embedding, int dimensions) {
        forKb(knowledgeBaseId).updateContentForChunk(tenantId, knowledgeBaseId, chunkId,
                content, embedding, dimensions);
    }

    @Override
    public void updateValidityForDocument(Long tenantId, Long knowledgeBaseId, Long documentId,
                                          Timestamp effectiveFrom, Timestamp effectiveTo) {
        forKb(knowledgeBaseId).updateValidityForDocument(tenantId, knowledgeBaseId, documentId,
                effectiveFrom, effectiveTo);
    }

    @Override
    public int copyDocumentVectors(Long tenantId, Long fromDocumentId, Long toDocumentId,
                                   int dimensions, String embeddingModel,
                                   Map<Integer, Long> chunkIdByIndex,
                                   Map<String, Object> metadataOverrides) {
        KnowledgeDocument doc = documentMapper.selectById(toDocumentId);
        VectorStore store = doc == null ? pgvector : forKb(doc.getKnowledgeBaseId());
        return store.copyDocumentVectors(tenantId, fromDocumentId, toDocumentId, dimensions,
                embeddingModel, chunkIdByIndex, metadataOverrides);
    }

    @Override
    public long countByDocument(Long tenantId, Long documentId, int dimensions) {
        KnowledgeDocument doc = documentMapper.selectById(documentId);
        return (doc == null ? pgvector : forKb(doc.getKnowledgeBaseId()))
                .countByDocument(tenantId, documentId, dimensions);
    }

    @Override
    public void deleteByDocument(Long tenantId, Long documentId) {
        // 切换后可能残留旧后端数据，两个后端都清理，避免再次切回时复活。
        pgvector.deleteByDocument(tenantId, documentId);
        QdrantVectorStore qdrant = qdrant();
        if (qdrant != null) {
            try {
                qdrant.deleteByDocument(tenantId, documentId);
            } catch (Exception e) {
                log.debug("Qdrant 文档删除跳过 doc={} err={}", documentId, e.getMessage());
            }
        }
    }

    @Override
    public void deleteByKnowledgeBase(Long tenantId, Long knowledgeBaseId) {
        pgvector.deleteByKnowledgeBase(tenantId, knowledgeBaseId);
        QdrantVectorStore qdrant = qdrant();
        if (qdrant != null) {
            try {
                qdrant.deleteByKnowledgeBase(tenantId, knowledgeBaseId);
            } catch (Exception e) {
                log.debug("Qdrant 知识库删除跳过 kb={} err={}", knowledgeBaseId, e.getMessage());
            }
        }
    }

    @Override
    public Map<String, Integer> reconcilePartialIndexes(Long tenantId) {
        return pgvector.reconcilePartialIndexes(tenantId);
    }

    @Override
    public Map<String, Object> partialIndexStatus(Long tenantId, Long knowledgeBaseId, int dimensions) {
        return forKb(knowledgeBaseId).partialIndexStatus(tenantId, knowledgeBaseId, dimensions);
    }

    public boolean supports(String backend) {
        return "QDRANT".equalsIgnoreCase(backend) && qdrant() != null;
    }

    private VectorStore forKb(Long knowledgeBaseId) {
        KnowledgeBase kb = knowledgeBaseId == null ? null : kbMapper.selectById(knowledgeBaseId);
        if (kb != null && "QDRANT".equalsIgnoreCase(kb.getVectorBackend()) && qdrant() != null) {
            return qdrant();
        }
        return pgvector;
    }

    private VectorStore qdrantOrPg() {
        QdrantVectorStore qdrant = qdrant();
        return qdrant == null ? pgvector : qdrant;
    }

    private QdrantVectorStore qdrant() {
        return qdrantProvider.getIfAvailable();
    }

    private String backendOfMetadata(Object metadata) {
        try {
            Map<?, ?> meta = metadata instanceof String text && !text.isBlank()
                    ? objectMapper.readValue(text, Map.class) : Map.of();
            Object kb = meta.get("knowledge_base_id");
            if (kb != null) {
                KnowledgeBase base = kbMapper.selectById(Long.valueOf(String.valueOf(kb)));
                if (base != null && "QDRANT".equalsIgnoreCase(base.getVectorBackend())) {
                    return "QDRANT";
                }
            }
        } catch (Exception ignored) {
        }
        return "PGVECTOR";
    }
}
