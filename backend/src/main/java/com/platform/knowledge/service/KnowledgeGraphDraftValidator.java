package com.platform.knowledge.service;

import com.platform.common.exception.BusinessException;
import com.platform.knowledge.entity.KnowledgeChunk;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.mapper.KnowledgeChunkMapper;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.platform.knowledge.service.KnowledgeGraphSupport.number;

/**
 * 图谱草稿证据引用校验：documentId/chunkId 必须存在且属于当前知识库，
 * 防止跨知识库或分段-文档错配的数据被写入图谱。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeGraphDraftValidator {

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;

    public void validateDraftRefs(Long kbId, List<Map<String, Object>> entities,
                                  List<Map<String, Object>> relations,
                                  Long documentId, Long chunkId) {
        Set<Long> documentIds = new HashSet<>();
        Set<Long> chunkIds = new HashSet<>();
        if (documentId != null) documentIds.add(documentId);
        if (chunkId != null) chunkIds.add(chunkId);
        for (List<Map<String, Object>> drafts : List.of(entities, relations)) {
            for (Map<String, Object> draft : drafts) {
                Long doc = number(draft.get("documentId"));
                Long chunk = number(draft.get("chunkId"));
                if (doc != null) documentIds.add(doc);
                if (chunk != null) chunkIds.add(chunk);
            }
        }
        Map<Long, Long> chunkDocuments = validateKnownRefs(kbId, documentIds, chunkIds);
        if (documentId != null && chunkId != null) {
            Long actual = chunkDocuments.get(chunkId);
            if (actual != null && !Objects.equals(actual, documentId)) {
                throw new BusinessException(400, "分段与文档不匹配");
            }
        }
        for (List<Map<String, Object>> drafts : List.of(entities, relations)) {
            for (Map<String, Object> draft : drafts) {
                Long doc = number(draft.get("documentId"));
                Long chunk = number(draft.get("chunkId"));
                if (doc == null || chunk == null) continue;
                Long actual = chunkDocuments.get(chunk);
                if (actual != null && !Objects.equals(actual, doc)) {
                    throw new BusinessException(400, "分段与文档不匹配");
                }
            }
        }
    }

    /** 校验前端可回传的 documentId/chunkId，避免把其他知识库的证据绑定到当前图谱。 */
    public Map<Long, Long> validateKnownRefs(Long kbId, Set<Long> documentIds, Set<Long> chunkIds) {
        Map<Long, Long> chunkDocuments = new HashMap<>();
        if (!documentIds.isEmpty()) {
            List<KnowledgeDocument> docs = documentMapper.selectBatchIds(documentIds);
            if (docs.size() != documentIds.size()) throw new BusinessException(400, "文档不存在");
            for (KnowledgeDocument doc : docs) {
                if (!Objects.equals(doc.getKnowledgeBaseId(), kbId)) {
                    throw new BusinessException(400, "文档不属于当前知识库");
                }
            }
        }
        if (!chunkIds.isEmpty()) {
            List<KnowledgeChunk> chunks = chunkMapper.selectBatchIds(chunkIds);
            if (chunks.size() != chunkIds.size()) throw new BusinessException(400, "分段不存在");
            for (KnowledgeChunk chunk : chunks) {
                if (!Objects.equals(chunk.getKnowledgeBaseId(), kbId)) {
                    throw new BusinessException(400, "分段不属于当前知识库");
                }
                if (!documentIds.isEmpty() && chunk.getDocumentId() != null
                        && !documentIds.contains(chunk.getDocumentId())) {
                    throw new BusinessException(400, "分段与文档不匹配");
                }
                if (chunk.getDocumentId() != null) chunkDocuments.put(chunk.getId(), chunk.getDocumentId());
            }
        }
        return chunkDocuments;
    }
}
