package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.platform.common.exception.BusinessException;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import com.platform.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文档人工复核、有效期维护与历史向量有效期重建。
 * 从 KnowledgeService 抽出，避免知识库主流程继续膨胀。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentMaintenanceService {

    private final KnowledgeDocumentMapper docMapper;
    private final VectorStore vectorRepository;
    private final AuditService auditService;
    private final KnowledgePermissionService permissionService;

    public Map<String, Object> resolveManualReview(Long kbId, Long documentId, String note,
                                                   KnowledgePermissionService.Viewer viewer) {
        permissionService.requireEdit(kbId, viewer);
        KnowledgeDocument doc = docMapper.selectById(documentId);
        if (doc == null || !Objects.equals(doc.getKnowledgeBaseId(), kbId)) {
            throw new BusinessException(404, "文档不存在");
        }
        String reviewNote = note == null ? null : note.substring(0, Math.min(500, note.length()));
        docMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getId, documentId)
                .set(KnowledgeDocument::getManualReviewStatus, "RESOLVED")
                .set(KnowledgeDocument::getManualReviewedAt, LocalDateTime.now())
                .set(KnowledgeDocument::getManualReviewedBy, viewer.userId())
                .set(KnowledgeDocument::getManualReviewNote, reviewNote));
        auditService.record(viewer.userId(), "knowledge.document.manual_review", "knowledge_document",
                String.valueOf(documentId), "SUCCESS",
                Map.of("kbId", kbId, "note", reviewNote == null ? "" : reviewNote));
        return Map.of("documentId", documentId, "manualReviewStatus", "RESOLVED");
    }

    public Map<String, Object> updateDocumentValidity(Long kbId, Long documentId,
                                                      Instant effectiveFrom, Instant effectiveTo,
                                                      KnowledgePermissionService.Viewer viewer) {
        permissionService.requireEdit(kbId, viewer);
        KnowledgeDocument doc = docMapper.selectById(documentId);
        if (doc == null || !Objects.equals(doc.getKnowledgeBaseId(), kbId)) {
            throw new BusinessException(404, "文档不存在");
        }
        if (effectiveFrom != null && effectiveTo != null && effectiveFrom.isAfter(effectiveTo)) {
            throw new BusinessException(400, "生效开始时间不能晚于生效结束时间");
        }
        LocalDateTime requestedFrom = effectiveFrom == null ? null : toLocalDateTime(effectiveFrom);
        LocalDateTime requestedTo = effectiveTo == null ? null : toLocalDateTime(effectiveTo);
        LocalDateTime actualFrom = requestedFrom != null ? requestedFrom : doc.getCreatedAt();
        if (actualFrom == null) actualFrom = LocalDateTime.now();
        if (requestedTo != null && actualFrom.isAfter(requestedTo)) {
            throw new BusinessException(400, "生效开始时间不能晚于生效结束时间");
        }
        LocalDateTime finalFrom = actualFrom;
        LocalDateTime finalTo = requestedTo;
        docMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getId, documentId)
                .set(KnowledgeDocument::getEffectiveFrom, requestedFrom)
                .set(KnowledgeDocument::getEffectiveTo, requestedTo));
        try {
            vectorRepository.updateValidityForDocument(doc.getTenantId(), kbId, documentId,
                    java.sql.Timestamp.valueOf(finalFrom),
                    finalTo == null ? null : java.sql.Timestamp.valueOf(finalTo));
        } catch (Exception e) {
            log.warn("文档有效期向量同步失败（MySQL 已更新）doc={} err={}", documentId, e.getMessage());
            throw new BusinessException(500, "文档有效期已保存，但向量有效期同步失败，请重新解析文档");
        }
        auditService.record(viewer.userId(), "kb.document.validity", "knowledge_document",
                String.valueOf(documentId), "SUCCESS",
                Map.of("kbId", kbId, "effectiveFrom", finalFrom.toString(),
                        "effectiveTo", finalTo == null ? "" : finalTo.toString()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documentId", documentId);
        result.put("effectiveFrom", finalFrom);
        result.put("effectiveTo", finalTo);
        return result;
    }

    public Map<String, Object> rebuildValidity(Long kbId, KnowledgePermissionService.Viewer viewer) {
        permissionService.requireEdit(kbId, viewer);
        List<KnowledgeDocument> docs = docMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getKnowledgeBaseId, kbId));
        int updated = 0;
        for (KnowledgeDocument doc : docs) {
            LocalDateTime from = doc.getEffectiveFrom() != null
                    ? doc.getEffectiveFrom() : doc.getCreatedAt();
            if (from == null) continue;
            vectorRepository.updateValidityForDocument(doc.getTenantId(), kbId, doc.getId(),
                    java.sql.Timestamp.valueOf(from),
                    doc.getEffectiveTo() == null ? null : java.sql.Timestamp.valueOf(doc.getEffectiveTo()));
            updated++;
        }
        auditService.record(viewer.userId(), "kb.validity.rebuild", "knowledge",
                String.valueOf(kbId), "SUCCESS", Map.of("documents", updated));
        return Map.of("documents", updated);
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
