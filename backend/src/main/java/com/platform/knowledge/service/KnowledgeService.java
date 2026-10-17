package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.billing.entity.BillingUsage;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.entity.KnowledgeChunk;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.mapper.KnowledgeBaseMapper;
import com.platform.knowledge.mapper.KnowledgeChunkMapper;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 知识库主流程（需求文档 3.6）：预签名直传 → 回调登记 → 异步解析分段 → 嵌入入向量库 → 检索。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeDocumentMapper docMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final BillingUsageMapper billingMapper;
    private final StorageService storage;
    private final EmbeddingService embeddingService;
    private final VectorRepository vectorRepository;
    private final DocumentParser parser;
    private final ObjectMapper objectMapper;

    public Map<String, Object> create(String name, String description, Long userId) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(name);
        kb.setDescription(description);
        kb.setTenantId(TenantContext.getTenantId());
        kb.setCreatedBy(userId);
        kb.setEmbeddingModel(embeddingService.model());
        kb.setIndexVersion("v1");
        kb.setCreatedAt(LocalDateTime.now());
        kbMapper.insert(kb);
        vectorRepository.ensureTenantSchema(kb.getTenantId());
        return Map.of("id", kb.getId());
    }

    public List<KnowledgeBase> list() {
        return kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .orderByDesc(KnowledgeBase::getCreatedAt));
    }

    public KnowledgeBase get(Long id) {
        KnowledgeBase kb = kbMapper.selectById(id);
        if (kb == null) throw new BusinessException(404, "知识库不存在");
        return kb;
    }

    /** 申请直传：返回预签名 PUT URL 与登记的 objectName（架构 3.8 路径规范）。 */
    public Map<String, Object> requestUpload(Long kbId, String fileName) {
        get(kbId);
        Long tenantId = TenantContext.getTenantId();
        String objectName = tenantId + "/knowledge/" + kbId + "/"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "/" + fileName;
        String uploadUrl = storage.presignPut(objectName);
        return Map.of("uploadUrl", uploadUrl, "objectName", objectName);
    }

    /** 回调：校验对象存在 + 路径前缀一致（防伪造，需求文档 7.2）→ 登记 → 异步解析。 */
    public Map<String, Object> callback(Long kbId, String objectName) {
        Long tenantId = TenantContext.getTenantId();
        String expectedPrefix = tenantId + "/knowledge/" + kbId + "/";
        if (objectName == null || !objectName.startsWith(expectedPrefix)) {
            throw new BusinessException(403, "objectName 与登记路径不一致");
        }
        if (!storage.exists(objectName)) {
            throw new BusinessException(400, "文件尚未上传成功（MinIO 中不存在）");
        }
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setKnowledgeBaseId(kbId);
        doc.setFileName(objectName.substring(objectName.lastIndexOf('/') + 1));
        doc.setObjectName(objectName);
        doc.setStatus("PENDING");
        doc.setChunkCount(0);
        doc.setTenantId(tenantId);
        doc.setCreatedAt(LocalDateTime.now());
        docMapper.insert(doc);

        parseAsync(doc.getId());
        return Map.of("documentId", doc.getId(), "status", doc.getStatus());
    }

    @Async
    void parseAsync(Long documentId) {
        KnowledgeDocument doc = docMapper.selectById(documentId);
        if (doc == null) return;
        try {
            updateStatus(doc, "PARSING", null, null);
            byte[] bytes = storage.get(doc.getObjectName());
            List<String> chunks = parser.parse(doc.getFileName(), bytes);
            float[][] vectors = embeddingService.embedBatch(chunks);

            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                KnowledgeChunk chunk = new KnowledgeChunk();
                chunk.setDocumentId(doc.getId());
                chunk.setKnowledgeBaseId(doc.getKnowledgeBaseId());
                chunk.setChunkIndex(i);
                chunk.setContent(chunks.get(i));
                chunk.setTenantId(doc.getTenantId());
                chunk.setCreatedAt(LocalDateTime.now());
                chunkMapper.insert(chunk);

                Map<String, Object> row = new HashMap<>();
                row.put("content", chunks.get(i));
                row.put("embedding", vectors[i]);
                row.put("metadata", objectMapper.writeValueAsString(Map.of(
                        "knowledge_base_id", doc.getKnowledgeBaseId(),
                        "document_id", doc.getId(),
                        "chunk_id", chunk.getId(),
                        "chunk_index", i,
                        "file_name", doc.getFileName())));
                rows.add(row);
            }
            vectorRepository.saveBatch(doc.getTenantId(), rows);
            updateStatus(doc, "DONE", chunks.size(), null);
            recordEmbeddingUsage(doc, chunks.size());
            log.info("文档解析完成：doc={} chunks={}", documentId, chunks.size());
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            log.warn("文档解析失败：doc={} err={}", documentId, root.getMessage());
            updateStatus(doc, "FAILED", null, root.getMessage());
        }
    }

    /** 检索测试（需求文档 3.6(4)）：query 嵌入 → PgVector topK + 阈值过滤。 */
    public List<Map<String, Object>> search(Long kbId, String query, Integer topK, Double threshold) {
        get(kbId);
        Long tenantId = TenantContext.getTenantId();
        float[] queryVector = embeddingService.embedOne(query);
        List<Map<String, Object>> hits = vectorRepository.search(tenantId, queryVector, kbId,
                topK == null ? 5 : topK,
                threshold == null ? 0.5 : threshold);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> hit : hits) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("content", hit.get("content"));
            item.put("score", hit.get("score"));
            item.put("metadata", hit.get("metadata"));
            result.add(item);
        }
        return result;
    }

    /** 文档分段浏览（架构 4.8 KnowledgeDetail/ChunkPanel 数据源）。 */
    public Map<String, Object> chunks(Long kbId, Long documentId, long page, long pageSize) {
        Page<KnowledgeChunk> result = chunkMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getKnowledgeBaseId, kbId)
                        .eq(KnowledgeChunk::getDocumentId, documentId)
                        .orderByAsc(KnowledgeChunk::getChunkIndex));
        List<Map<String, Object>> list = result.getRecords().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("chunkIndex", c.getChunkIndex());
            m.put("content", c.getContent());
            return m;
        }).toList();
        return Map.of("list", list, "total", result.getTotal(), "page", page, "pageSize", pageSize);
    }

    public List<KnowledgeDocument> documents(Long kbId) {
        get(kbId);
        return docMapper.selectList(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getKnowledgeBaseId, kbId)
                .orderByDesc(KnowledgeDocument::getCreatedAt));
    }

    private void updateStatus(KnowledgeDocument doc, String status, Integer chunkCount, String error) {
        KnowledgeDocument patch = new KnowledgeDocument();
        patch.setId(doc.getId());
        patch.setStatus(status);
        patch.setChunkCount(chunkCount);
        patch.setErrorMessage(error);
        docMapper.updateById(patch);
    }

    private void recordEmbeddingUsage(KnowledgeDocument doc, int chunkCount) {
        try {
            BillingUsage u = new BillingUsage();
            u.setTenantId(doc.getTenantId());
            u.setKnowledgeBaseId(doc.getKnowledgeBaseId());
            u.setSource("embedding");
            u.setModelName(embeddingService.model());
            u.setInputTokens((long) chunkCount);
            u.setOutputTokens(0L);
            u.setStatDate(LocalDate.now());
            u.setCreatedAt(LocalDateTime.now());
            billingMapper.insert(u);
        } catch (Exception e) {
            log.warn("嵌入用量记录失败：{}", e.getMessage());
        }
    }
}
