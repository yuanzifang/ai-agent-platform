package com.platform.knowledge.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 向量存储抽象。默认实现为 PostgreSQL + pgvector；可选 Qdrant，Milvus 保留扩展点。
 *
 * <p>接口只暴露知识库业务需要的能力，具体后端的 collection/schema/索引维护由实现处理。
 * pgvector 特有的 partial index 维护有默认空实现，Qdrant/Milvus 不需要实现这些方法。</p>
 */
public interface VectorStore {

    String provider();

    void ensureTenantSchema(Long tenantId);

    void saveBatch(Long tenantId, List<Map<String, Object>> rows);

    void saveBatch(Long tenantId, List<Map<String, Object>> rows, int dimensions);

    List<Map<String, Object>> search(Long tenantId, float[] queryVector, Long knowledgeBaseId,
                                     int topK, double threshold);

    List<Map<String, Object>> search(Long tenantId, float[] queryVector, Long knowledgeBaseId,
                                     int topK, double threshold, int dimensions);

    List<Map<String, Object>> search(Long tenantId, float[] queryVector, Long knowledgeBaseId,
                                     int topK, double threshold, int dimensions,
                                     Long userId, boolean tenantWide);

    List<Map<String, Object>> search(Long tenantId, float[] queryVector, Long knowledgeBaseId,
                                     int topK, double threshold, int dimensions,
                                     Long userId, boolean tenantWide,
                                     Instant effectiveAfter, Instant effectiveBefore);

    void updateAclForKnowledgeBase(Long tenantId, Long knowledgeBaseId,
                                   List<Long> allowedUsers, boolean tenantWide);

    void updateAclForChunk(Long tenantId, Long knowledgeBaseId, Long chunkId,
                           List<Long> allowedUsers, boolean tenantWide);

    void updateContentForChunk(Long tenantId, Long knowledgeBaseId, Long chunkId,
                               String content, float[] embedding, int dimensions);

    void updateValidityForDocument(Long tenantId, Long knowledgeBaseId, Long documentId,
                                   java.sql.Timestamp effectiveFrom, java.sql.Timestamp effectiveTo);

    int copyDocumentVectors(Long tenantId, Long fromDocumentId, Long toDocumentId,
                            int dimensions, String embeddingModel,
                            Map<Integer, Long> chunkIdByIndex,
                            Map<String, Object> metadataOverrides);

    long countByDocument(Long tenantId, Long documentId, int dimensions);

    void deleteByDocument(Long tenantId, Long documentId);

    void deleteByKnowledgeBase(Long tenantId, Long knowledgeBaseId);

    /** pgvector partial HNSW 维护；其他后端默认无操作。 */
    default Map<String, Integer> reconcilePartialIndexes(Long tenantId) {
        return Map.of("created", 0, "dropped", 0, "desired", 0);
    }

    /** pgvector partial HNSW 状态；其他后端返回后端基本信息。 */
    default Map<String, Object> partialIndexStatus(Long tenantId, Long knowledgeBaseId, int dimensions) {
        return Map.of("provider", provider(), "knowledgeBaseId", knowledgeBaseId);
    }
}
