package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 文档内容哈希与存量 SHA256 回填。
 * 哈希用于重复文件复用和解析稳定性判断；回填按条读取对象存储，避免一次载入大量对象。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeSourceHashService {

    private final KnowledgeDocumentMapper docMapper;
    private final StorageService storage;
    private final KnowledgePermissionService permissionService;

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    public Map<String, Object> backfill(Long kbId, int limit,
                                        KnowledgePermissionService.Viewer viewer) {
        permissionService.requireEdit(kbId, viewer);
        int max = Math.max(1, Math.min(limit, 100));
        List<KnowledgeDocument> docs = docMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getKnowledgeBaseId, kbId)
                        .eq(KnowledgeDocument::getStatus, "DONE")
                        .isNull(KnowledgeDocument::getSourceSha256)
                        .last("LIMIT " + max));
        return backfillRows(docs);
    }

    public Map<String, Object> backfillForTenant(int limit) {
        int max = Math.max(1, Math.min(limit, 100));
        List<KnowledgeDocument> docs = docMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getStatus, "DONE")
                        .isNull(KnowledgeDocument::getSourceSha256)
                        .orderByAsc(KnowledgeDocument::getId)
                        .last("LIMIT " + max));
        return backfillRows(docs);
    }

    private Map<String, Object> backfillRows(List<KnowledgeDocument> docs) {
        int updated = 0;
        int failed = 0;
        for (KnowledgeDocument doc : docs) {
            try {
                String sha = sha256(storage.get(doc.getObjectName()));
                if (sha == null) {
                    failed++;
                    continue;
                }
                docMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getId, doc.getId())
                        .set(KnowledgeDocument::getSourceSha256, sha));
                updated++;
            } catch (Exception e) {
                failed++;
                log.warn("回填 source_sha256 失败 doc={} err={}", doc.getId(), e.getMessage());
            }
        }
        return Map.of("scanned", docs.size(), "updated", updated, "failed", failed);
    }
}
