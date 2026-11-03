package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.platform.billing.entity.BillingUsage;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.billing.service.PricingService;
import com.platform.common.exception.BusinessException;
import com.platform.common.security.CryptoService;
import com.platform.knowledge.entity.KnowledgeChunk;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.entity.KnowledgeDocumentImage;
import com.platform.knowledge.entity.KnowledgeParent;
import com.platform.knowledge.entity.KnowledgeVectorOutbox;
import com.platform.knowledge.mapper.KnowledgeChunkMapper;
import com.platform.knowledge.mapper.KnowledgeDocumentImageMapper;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import com.platform.knowledge.mapper.KnowledgeParentMapper;
import com.platform.knowledge.mapper.KnowledgeVectorOutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import static com.platform.knowledge.service.KnowledgeSettingsSupport.usersJson;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** 文档图片、解析状态、复用与 outbox 收尾等文档级支撑逻辑。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentSupport {

    private final KnowledgeDocumentMapper docMapper;
    private final KnowledgeDocumentImageMapper documentImageMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgePermissionService permissionService;
    private final StorageService storage;
    private final KnowledgeChunkStore chunkStore;
    private final KnowledgeDocumentMediaStore mediaStore;
    private final KnowledgeGraphService graphService;
    private final BillingUsageMapper billingMapper;
    private final PricingService pricingService;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;
    private final CryptoService cryptoService;
    private final VectorStore vectorRepository;
    private final KnowledgeVectorOutboxMapper vectorOutboxMapper;
    private final KnowledgeParentMapper parentMapper;

    @Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("jdbcTemplate")
    private JdbcTemplate mysqlJdbcTemplate;

    /** 文档级 PDF 密码落库前 AES-GCM 加密；密码不进入日志或知识库级配置。 */
    String protectPdfPassword(String json) {
        if (json == null || json.isBlank()) return json;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> config = objectMapper.readValue(json, Map.class);
            Object raw = config.get("pdfPassword");
            if (raw == null || String.valueOf(raw).isBlank()) return json;
            String password = String.valueOf(raw);
            config.put("pdfPassword", password.startsWith("v1:")
                    ? password : cryptoService.encrypt(password));
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new BusinessException(400, "解析配置格式不正确");
        }
    }

    void markOutboxPending(KnowledgeDocument doc) {
        if (vectorOutboxMapper == null || doc == null || doc.getId() == null) return;
        try {
            LocalDateTime now = LocalDateTime.now();
            KnowledgeVectorOutbox task = vectorOutboxMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeVectorOutbox>()
                            .eq(KnowledgeVectorOutbox::getDocumentId, doc.getId()));
            if (task == null) {
                task = new KnowledgeVectorOutbox();
                task.setTenantId(doc.getTenantId());
                task.setDocumentId(doc.getId());
                task.setStatus("PENDING");
                task.setAttempts(0);
                task.setAvailableAt(now.plusMinutes(5));
                task.setCreatedAt(now);
                task.setUpdatedAt(now);
                vectorOutboxMapper.insert(task);
            } else {
                vectorOutboxMapper.update(null, new LambdaUpdateWrapper<KnowledgeVectorOutbox>()
                        .eq(KnowledgeVectorOutbox::getId, task.getId())
                        .set(KnowledgeVectorOutbox::getStatus, "PENDING")
                        .set(KnowledgeVectorOutbox::getAvailableAt, now.plusMinutes(5))
                        .set(KnowledgeVectorOutbox::getPayload, null)
                        .set(KnowledgeVectorOutbox::getEmbeddingModel, null)
                        .set(KnowledgeVectorOutbox::getDimensions, null)
                        .set(KnowledgeVectorOutbox::getUpdatedAt, now));
            }
        } catch (Exception e) {
            log.debug("outbox 写入失败（忽略）doc={}：{}", doc.getId(), e.getMessage());
        }
    }

    void markOutboxDone(KnowledgeDocument doc) {
        if (vectorOutboxMapper == null || doc == null || doc.getId() == null) return;
        try {
            vectorOutboxMapper.update(null, new LambdaUpdateWrapper<KnowledgeVectorOutbox>()
                    .eq(KnowledgeVectorOutbox::getDocumentId, doc.getId())
                    .set(KnowledgeVectorOutbox::getStatus, "DONE")
                    .set(KnowledgeVectorOutbox::getLastError, null)
                    .set(KnowledgeVectorOutbox::getPayload, null)
                    .set(KnowledgeVectorOutbox::getEmbeddingModel, null)
                    .set(KnowledgeVectorOutbox::getDimensions, null)
                    .set(KnowledgeVectorOutbox::getUpdatedAt, LocalDateTime.now()));
        } catch (Exception e) {
            log.debug("outbox 完成标记失败（忽略）doc={}：{}", doc.getId(), e.getMessage());
        }
    }

    void markOutboxFailed(KnowledgeDocument doc, String error) {
        if (vectorOutboxMapper == null || doc == null || doc.getId() == null) return;
        try {
            String message = error == null ? null : error.substring(0, Math.min(1000, error.length()));
            vectorOutboxMapper.update(null, new LambdaUpdateWrapper<KnowledgeVectorOutbox>()
                    .eq(KnowledgeVectorOutbox::getDocumentId, doc.getId())
                    .set(KnowledgeVectorOutbox::getStatus, "FAILED")
                    .set(KnowledgeVectorOutbox::getLastError, message)
                    .set(KnowledgeVectorOutbox::getUpdatedAt, LocalDateTime.now()));
        } catch (Exception e) {
            log.debug("outbox 失败标记失败（忽略）doc={}：{}", doc.getId(), e.getMessage());
        }
    }

    /** 补偿清理：重试前删除可能已写入的部分分段与向量，并清掉该文档贡献的自动图谱。 */
    void cleanupPartial(KnowledgeDocument doc) {
        if (vectorOutboxMapper != null && doc != null) {
            try {
                vectorOutboxMapper.update(null, new LambdaUpdateWrapper<KnowledgeVectorOutbox>()
                        .eq(KnowledgeVectorOutbox::getDocumentId, doc.getId())
                        .set(KnowledgeVectorOutbox::getPayload, null)
                        .set(KnowledgeVectorOutbox::getEmbeddingModel, null)
                        .set(KnowledgeVectorOutbox::getDimensions, null));
            } catch (Exception ignored) { }
        }
        try {
            chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunk>()
                    .eq(KnowledgeChunk::getDocumentId, doc.getId()));
        } catch (Exception e) {
            log.warn("清理失败分段失败 doc={}：{}", doc.getId(), e.getMessage());
        }
        deleteParentsQuietly(doc.getId());
        deleteVectorsQuietly(doc.getTenantId(), doc.getId());
        try {
            graphService.deleteByDocument(doc.getKnowledgeBaseId(), doc.getId());
        } catch (Exception e) {
            log.warn("清理文档自动图谱失败 doc={}：{}", doc.getId(), e.getMessage());
        }
    }

    void deleteParentsQuietly(Long documentId) {
        if (parentMapper == null) return;
        try {
            parentMapper.delete(new LambdaQueryWrapper<KnowledgeParent>()
                    .eq(KnowledgeParent::getDocumentId, documentId));
        } catch (Exception e) {
            log.warn("清理父块失败 doc={}：{}", documentId, e.getMessage());
        }
    }

    void deleteVectorsQuietly(Long tenantId, Long documentId) {
        try {
            vectorRepository.deleteByDocument(tenantId, documentId);
        } catch (Exception e) {
            log.warn("删除文档向量失败 doc={} err={}（数据库记录继续清理）", documentId, e.getMessage());
        }
    }

    /** 文档图片列表（预签名 URL；需知识库可见权限）。 */
    public List<Map<String, Object>> documentImages(Long kbId, Long docId,
                                                     KnowledgePermissionService.Viewer viewer) {
        permissionService.requireView(kbId, viewer);
        KnowledgeDocument doc = docMapper.selectById(docId);
        if (doc == null || !Objects.equals(doc.getKnowledgeBaseId(), kbId)) {
            throw new BusinessException(404, "文档不存在");
        }
        return documentImageMapper.selectList(new LambdaQueryWrapper<KnowledgeDocumentImage>()
                        .eq(KnowledgeDocumentImage::getDocumentId, docId)
                        .orderByAsc(KnowledgeDocumentImage::getId))
                .stream().map(row -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", row.getId());
                    m.put("fileName", row.getFileName());
                    m.put("mimeType", row.getMimeType());
                    m.put("sizeBytes", row.getSizeBytes());
                    m.put("url", storage.presignGet(row.getObjectName()));
                    return m;
                }).toList();
    }

    void updateStatus(KnowledgeDocument doc, String status, Integer chunkCount, String error) {
        KnowledgeDocument patch = new KnowledgeDocument();
        patch.setId(doc.getId());
        patch.setStatus(status);
        patch.setChunkCount(chunkCount);
        // 空串显式清理历史失败信息；MyBatis-Plus updateById 默认忽略 null。
        patch.setErrorMessage(error == null ? "" : error);
        if ("PARSING".equals(status)) patch.setParseStartedAt(LocalDateTime.now());
        if (doc.getUpgradeJobId() != null) {
            if ("DONE".equals(status)) patch.setUpgradeStatus("SUCCEEDED");
            else if ("FAILED".equals(status)) patch.setUpgradeStatus("FAILED");
        }
        docMapper.updateById(patch);
    }

    KnowledgeDocument findReusableDocument(KnowledgeDocument doc, String sourceSha,
                                                   String parseFingerprint) {
        // 老文档没有解析指纹时不做复用，避免用未知解析配置的旧结果覆盖新上传。
        if (sourceSha == null || parseFingerprint == null) return null;
        try {
            List<KnowledgeDocument> candidates = docMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeDocument>()
                            .eq(doc.getTenantId() != null, KnowledgeDocument::getTenantId,
                                    doc.getTenantId())
                            .eq(KnowledgeDocument::getSourceSha256, sourceSha)
                            .eq(KnowledgeDocument::getParseFingerprint, parseFingerprint)
                            .eq(KnowledgeDocument::getParserVersion, DocumentParser.PARSER_VERSION)
                            .ne(KnowledgeDocument::getId, doc.getId())
                            .eq(KnowledgeDocument::getStatus, "DONE")
                            .orderByDesc(KnowledgeDocument::getCreatedAt)
                            .last("LIMIT 5"));
            if (candidates != null && !candidates.isEmpty()) return candidates.get(0);
        } catch (Exception e) {
            log.debug("重复文件查询失败（忽略）doc={}：{}", doc.getId(), e.getMessage());
        }
        return null;
    }

    void recordParserVersion(KnowledgeDocument doc) {
        if (DocumentParser.PARSER_VERSION.equals(doc.getParserVersion())) return;
        try {
            doc.setParserVersion(DocumentParser.PARSER_VERSION);
            docMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                    .eq(KnowledgeDocument::getId, doc.getId())
                    .set(KnowledgeDocument::getParserVersion, DocumentParser.PARSER_VERSION));
        } catch (Exception e) {
            log.debug("解析器版本记录失败（忽略）doc={}：{}", doc.getId(), e.getMessage());
        }
    }

    void recordEmbeddingInfo(KnowledgeDocument doc, String model, int dimensions) {
        doc.setEmbeddingModel(model);
        doc.setEmbeddingDimensions(dimensions);
        try {
            docMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                    .eq(KnowledgeDocument::getId, doc.getId())
                    .set(KnowledgeDocument::getEmbeddingModel, model)
                    .set(KnowledgeDocument::getEmbeddingDimensions, dimensions));
        } catch (Exception e) {
            log.debug("embedding 信息记录失败（忽略）doc={}：{}", doc.getId(), e.getMessage());
        }
    }

    void recordParseFingerprint(KnowledgeDocument doc, String fingerprint) {
        if (fingerprint == null) return;
        try {
            doc.setParseFingerprint(fingerprint);
            docMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                    .eq(KnowledgeDocument::getId, doc.getId())
                    .set(KnowledgeDocument::getParseFingerprint, fingerprint));
        } catch (Exception e) {
            log.debug("解析配置指纹记录失败（忽略）doc={}：{}", doc.getId(), e.getMessage());
        }
    }

    /**
     * 复用同租户、相同 SHA256 且解析配置指纹一致文档的分段、父块和向量，
     * 跳过重复解析/嵌入。任一环节失败都会清理新文档残留并回退正常解析。
     */
    boolean reuseDocument(KnowledgeDocument doc, byte[] bytes, KnowledgeDocument source,
                                  KnowledgePermissionService.Acl acl, String embeddingModel,
                                  int dimensions, List<String> warnings) {
        try {
            List<KnowledgeChunk> oldChunks = chunkMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeChunk>()
                            .eq(KnowledgeChunk::getDocumentId, source.getId())
                            .orderByAsc(KnowledgeChunk::getChunkIndex));
            if (oldChunks == null || oldChunks.isEmpty()) return false;
            Map<Integer, String> parentContents = new HashMap<>();
            if (parentMapper != null) {
                for (KnowledgeParent parent : parentMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeParent>()
                                .eq(KnowledgeParent::getDocumentId, source.getId()))) {
                    if (parent.getParentIndex() != null) {
                        parentContents.put(parent.getParentIndex(), parent.getContent());
                    }
                }
            }
            List<KnowledgeChunk> newChunks = new ArrayList<>(oldChunks.size());
            for (KnowledgeChunk old : oldChunks) {
                KnowledgeChunk chunk = new KnowledgeChunk();
                chunk.setDocumentId(doc.getId());
                chunk.setKnowledgeBaseId(doc.getKnowledgeBaseId());
                chunk.setChunkIndex(old.getChunkIndex());
                chunk.setParentIndex(old.getParentIndex());
                String parentContent = old.getParentContent();
                if ((parentContent == null || parentContent.isBlank()) && old.getParentIndex() != null) {
                    parentContent = parentContents.get(old.getParentIndex());
                }
                chunk.setParentContent(parentContent);
                chunk.setContent(old.getContent());
                chunk.setDocumentType(old.getDocumentType());
                chunk.setSectionType(old.getSectionType());
                chunk.setChunkLevel(old.getChunkLevel());
                chunk.setParentKey(doc.getId() + ":" + old.getParentIndex());
                chunk.setDomainKeywords(old.getDomainKeywords());
                chunk.setRelevanceScore(old.getRelevanceScore());
                chunk.setRegulatoryCategory(old.getRegulatoryCategory());
                chunk.setPageFrom(old.getPageFrom());
                chunk.setPageTo(old.getPageTo());
                chunk.setAllowedUsers(usersJson(acl.allowedUsers()));
                chunk.setTenantVisible(acl.tenantWide() ? 1 : 0);
                chunk.setTenantId(doc.getTenantId());
                chunk.setCreatedAt(LocalDateTime.now());
                newChunks.add(chunk);
            }
            Map<Integer, Long> chunkIds = chunkStore.persist(doc, newChunks, warnings);
            if (chunkIds.size() != newChunks.size()) {
                cleanupPartial(doc);
                return false;
            }
            LocalDateTime effectiveFrom = doc.getEffectiveFrom() != null
                    ? doc.getEffectiveFrom() : (doc.getCreatedAt() == null ? LocalDateTime.now() : doc.getCreatedAt());
            LocalDateTime effectiveTo = doc.getEffectiveTo();
            LocalDateTime now = LocalDateTime.now();
            Map<String, Object> overrides = new LinkedHashMap<>();
            overrides.put("knowledge_base_id", doc.getKnowledgeBaseId());
            overrides.put("document_id", doc.getId());
            overrides.put("file_name", doc.getFileName());
            overrides.put("document_created_at",
                    doc.getCreatedAt() == null ? null : doc.getCreatedAt().toString());
            overrides.put("allowed_users", acl.allowedUsers().stream().map(String::valueOf).toList());
            overrides.put("tenant_visible", acl.tenantWide());
            overrides.put("embedding_model", embeddingModel);
            overrides.put("effective_from", effectiveFrom.toString());
            overrides.put("effective_to", effectiveTo == null ? null : effectiveTo.toString());
            overrides.put("created_at", now.toString());
            overrides.put("updated_at", now.toString());
            int copied = vectorRepository.copyDocumentVectors(doc.getTenantId(), source.getId(),
                    doc.getId(), dimensions, embeddingModel, chunkIds, overrides);
            if (copied < newChunks.size()) {
                cleanupPartial(doc);
                return false;
            }
            if (mysqlJdbcTemplate != null) {
                long vectorCount = vectorRepository.countByDocument(doc.getTenantId(), doc.getId(), dimensions);
                if (vectorCount != newChunks.size()) {
                    cleanupPartial(doc);
                    return false;
                }
            }
            try {
                mediaStore.store(doc, bytes);
            } catch (Exception e) {
                log.warn("复用文档图片存储失败（忽略）doc={} err={}", doc.getId(), e.getMessage());
            }
            String reuseMessage = Objects.equals(source.getKnowledgeBaseId(), doc.getKnowledgeBaseId())
                    ? "已复用相同文件「" + source.getFileName() + "」的解析结果"
                    : "已复用同租户相同文件的解析结果";
            warnings.add(reuseMessage);
            if (source.getParseWarning() != null && source.getParseWarning().contains("人工复核")) {
                warnings.add("人工复核：复用了需人工复核的源文档解析结果，请核对");
            }
            recordParserVersion(doc);
            recordEmbeddingInfo(doc, embeddingModel, dimensions);
            updateStatus(doc, "DONE", newChunks.size(), null);
            markOutboxDone(doc);
            storeParseWarning(doc, warnings);
            try {
                graphService.autoExtractAfterParse(doc.getTenantId(), doc.getKnowledgeBaseId(), doc.getId());
            } catch (Exception e) {
                log.warn("复用文档自动图谱失败（忽略）doc={} err={}", doc.getId(), e.getMessage());
            }
            log.info("文档复用完成：doc={} source={} chunks={}", doc.getId(), source.getId(), copied);
            return true;
        } catch (Exception e) {
            log.warn("重复文件复用失败，回退重新解析 doc={} source={} err={}",
                    doc.getId(), source.getId(), e.getMessage());
            cleanupPartial(doc);
            return false;
        }
    }

    void recordSourceHash(KnowledgeDocument doc, String sha256, List<String> warnings) {
        if (sha256 == null) return;
        try {
            doc.setSourceSha256(sha256);
            docMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                    .eq(KnowledgeDocument::getId, doc.getId())
                    .set(KnowledgeDocument::getSourceSha256, sha256));
            List<KnowledgeDocument> duplicates = docMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeDocument>()
                            .eq(KnowledgeDocument::getKnowledgeBaseId, doc.getKnowledgeBaseId())
                            .eq(KnowledgeDocument::getSourceSha256, sha256)
                            .ne(KnowledgeDocument::getId, doc.getId())
                            .eq(KnowledgeDocument::getStatus, "DONE")
                            .last("LIMIT 1"));
            if (duplicates != null && !duplicates.isEmpty()) {
                warnings.add("检测到相同文件「" + duplicates.get(0).getFileName()
                        + "」；相同文本嵌入会命中缓存，如需清理可删除重复文档");
            }
        } catch (Exception e) {
            log.debug("文件哈希记录失败（忽略）doc={}：{}", doc.getId(), e.getMessage());
        }
    }

    void storeParseWarning(KnowledgeDocument doc, List<String> warnings) {
        String text = warnings == null ? "" : warnings.stream()
                .filter(message -> message != null && !message.isBlank())
                .distinct()
                .collect(java.util.stream.Collectors.joining("；"));
        if (text.length() > 1000) text = text.substring(0, 1000);
        try {
            LambdaUpdateWrapper<KnowledgeDocument> update = new LambdaUpdateWrapper<KnowledgeDocument>()
                    .eq(KnowledgeDocument::getId, doc.getId())
                    .set(KnowledgeDocument::getParseWarning, text);
            if (text.contains("人工复核")) {
                update.set(KnowledgeDocument::getManualReviewStatus, "PENDING");
            } else {
                update.set(KnowledgeDocument::getManualReviewStatus, null);
            }
            docMapper.update(null, update);
        } catch (Exception e) {
            log.warn("解析告警写入失败（不影响文档状态）doc={} err={}", doc.getId(), e.getMessage());
        }
    }

    void recordEmbeddingUsage(KnowledgeDocument doc, int chunkCount, String embeddingModel) {
        try {
            BillingUsage u = new BillingUsage();
            u.setTenantId(doc.getTenantId());
            u.setKnowledgeBaseId(doc.getKnowledgeBaseId());
            u.setSource("embedding");
            String billedModel = embeddingModel == null || embeddingModel.isBlank()
                    ? embeddingService.model() : embeddingModel;
            u.setModelName(billedModel);
            u.setInputTokens((long) chunkCount);
            u.setOutputTokens(0L);
            u.setCost(pricingService.cost(doc.getTenantId(), billedModel, chunkCount, 0));
            u.setStatDate(LocalDate.now());
            u.setCreatedAt(LocalDateTime.now());
            billingMapper.insert(u);
        } catch (Exception e) {
            log.warn("嵌入用量记录失败：{}", e.getMessage());
        }
    }
}
