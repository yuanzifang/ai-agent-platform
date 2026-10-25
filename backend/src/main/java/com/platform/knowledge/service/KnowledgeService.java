package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.agent.entity.AgentKnowledgeBase;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.mapper.AgentKnowledgeBaseMapper;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.billing.entity.BillingUsage;
import com.platform.billing.service.PricingService;
import com.platform.billing.service.QuotaService;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.security.CryptoService;
import com.platform.common.exception.BusinessException;
import com.platform.common.security.NetworkPolicyService;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.entity.KnowledgeChunk;
import com.platform.knowledge.entity.KnowledgeDocumentImage;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.mapper.KnowledgeBaseMapper;
import com.platform.knowledge.mapper.KnowledgeChunkMapper;
import com.platform.knowledge.mapper.KnowledgeDocumentImageMapper;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import com.platform.system.entity.SysUser;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.service.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * 知识库主流程（需求文档 3.6）：预签名直传 → 回调登记 → 异步解析分段 → 嵌入入向量库 → 检索；
 * 含知识库/文档的编辑、删除（MinIO/PgVector 级联）与解析失败重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private static final String CHART_CHUNK_MARKER = "【图表数据";

    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeDocumentMapper docMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeDocumentImageMapper documentImageMapper;
    private final BillingUsageMapper billingMapper;
    private final PricingService pricingService;
    private final QuotaService quotaService;
    private final StorageService storage;
    private final EmbeddingService embeddingService;
    private final VectorRepository vectorRepository;
    private final HybridRetrievalService hybridRetrieval;
    private final DocumentParser parser;
    private final KnowledgeParseConfigResolver parseConfigResolver;
    private final ImageUnderstandingService imageUnderstanding;
    private final KnowledgeGraphService graphService;
    private final ObjectMapper objectMapper;
    private final KnowledgePermissionService permissionService;
    private final SysUserMapper userMapper;
    private final AgentKnowledgeBaseMapper agentKbMapper;
    private final AgentRegistryMapper agentRegistryMapper;
    private final AuditService auditService;
    private final NetworkPolicyService networkPolicy;
    private final CryptoService cryptoService;

    /** 解析失败重试上限（需求 3.6.3(2)：默认 3 次，超限后需人工介入）。 */
    @Value("${knowledge.parse.max-retries:3}")
    private int maxParseRetries;

    /** 自动重试次数（指数退避），失败后才置 FAILED，用户仍可手动重试。 */
    @Value("${knowledge.parse.auto-retries:2}")
    private int autoParseRetries;

    /** URL 导入最大响应体（默认 5MB）。 */
    @Value("${knowledge.import.max-bytes:5242880}")
    private int importMaxBytes;

    /** 文档解析专用线程池（架构 2.3）；队列满时 CallerRuns 保证不丢任务。 */
    @Resource(name = "documentParseExecutor")
    private ThreadPoolTaskExecutor documentParseExecutor;

    public Map<String, Object> create(String name, String description, String visibility,
                                      String embeddingModel, Boolean graphEnabled, Long userId) {
        quotaService.checkKnowledgeBaseCreate();
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(name);
        kb.setDescription(description);
        kb.setTenantId(TenantContext.getTenantId());
        kb.setCreatedBy(userId);
        kb.setVisibility("PRIVATE".equalsIgnoreCase(visibility) ? "PRIVATE" : "TENANT");
        // 知识图谱按知识库选择开启；未明确开启时不自动抽取，避免无差别消耗模型额度
        kb.setGraphEnabled(Boolean.TRUE.equals(graphEnabled));
        String model = embeddingModel == null || embeddingModel.isBlank()
                ? embeddingService.model() : embeddingModel.trim();
        if (!embeddingService.supports(model)) {
            throw new BusinessException(400, "未配置的 embedding 模型：" + model);
        }
        kb.setEmbeddingModel(model);
        kb.setIndexVersion("v1");
        kb.setCreatedAt(LocalDateTime.now());
        kbMapper.insert(kb);
        vectorRepository.ensureTenantSchema(kb.getTenantId());
        return Map.of("id", kb.getId());
    }

    /** 编辑名称/描述（需求 3.6.3(6)）；需编辑权限。 */
    public void update(Long id, String name, String description, String embeddingModel,
                       Boolean graphEnabled, Map<String, Object> parseConfig,
                       KnowledgePermissionService.Viewer viewer) {
        String parseConfigJson;
        try {
            if (parseConfig == null) {
                parseConfigJson = null;
            } else {
                Map<String, Object> safeConfig = new LinkedHashMap<>(parseConfig);
                // PDF 密码只允许文档级携带，知识库级配置不保存密码。
                safeConfig.remove("pdfPassword");
                parseConfigJson = objectMapper.writeValueAsString(safeConfig);
            }
        } catch (Exception e) {
            throw new BusinessException(400, "解析配置不是合法 JSON");
        }
        // 部分更新语义（解析设置对话框只发 parseConfig）：空字段保留现值
        KnowledgeBase kb = permissionService.requireEdit(id, viewer);
        if (name != null && name.isBlank()) name = null;
        if (name == null) name = kb.getName();
        String model = kb.getEmbeddingModel();
        if (embeddingModel != null && !embeddingModel.isBlank()
                && !embeddingModel.trim().equals(model)) {
            if (!embeddingService.supports(embeddingModel.trim())) {
                throw new BusinessException(400, "未配置的 embedding 模型：" + embeddingModel);
            }
            long chunks = chunkMapper.selectCount(new LambdaQueryWrapper<KnowledgeChunk>()
                    .eq(KnowledgeChunk::getKnowledgeBaseId, id));
            if (chunks > 0) {
                throw new BusinessException(400,
                        "已有文档使用当前 embedding 模型，不能直接切换；请先清空文档或新建知识库");
            }
            model = embeddingModel.trim();
        }
        final String finalModel = model;
        String descriptionValue = description == null ? null
                : (description.isBlank() ? null : description.trim());
        String parseConfigValue = parseConfigJson == null ? null
                : (parseConfigJson.trim().isEmpty() || "{}".equals(parseConfigJson.trim())
                        ? null : parseConfigJson.trim());
        kbMapper.update(null, new LambdaUpdateWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, id)
                .set(KnowledgeBase::getName, name.trim())
                // null = 未提交该字段，保留原值；空串 = 显式清空描述
                .set(description != null, KnowledgeBase::getDescription, descriptionValue)
                .set(KnowledgeBase::getEmbeddingModel, finalModel)
                .set(graphEnabled != null, KnowledgeBase::getGraphEnabled, graphEnabled)
                // null = 保留原解析配置；空对象 = 清除覆盖并回退默认
                .set(parseConfig != null, KnowledgeBase::getParseConfig, parseConfigValue));
        auditService.record(viewer.userId(), "kb.update", "knowledge", String.valueOf(id), "SUCCESS",
                Map.of("name", name.trim()));
        log.info("知识库已更新：id={} name={}", kb.getId(), name.trim());
    }

    /** 列表：按可见性过滤 + 名称/描述搜索 + 分页，附带文档数/分段数/创建人/可见范围（需求 3.6.3(6)）。 */
    public Map<String, Object> list(String keyword, String visibility, long page, long pageSize,
                                    KnowledgePermissionService.Viewer viewer) {
        long p = Math.max(page, 1);
        long size = Math.min(Math.max(pageSize, 1), 100);
        List<KnowledgeBase> all = kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(visibility != null && !visibility.isBlank(), KnowledgeBase::getVisibility,
                        visibility == null ? null : visibility.trim().toUpperCase())
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(KnowledgeBase::getName, keyword)
                        .or().like(KnowledgeBase::getDescription, keyword))
                .orderByDesc(KnowledgeBase::getCreatedAt));
        List<KnowledgeBase> visible = all.stream().filter(kb -> permissionService.canView(kb, viewer)).toList();
        long total = visible.size();
        long offset = p - 1 > Long.MAX_VALUE / size ? total : (p - 1) * size;
        int from = (int) Math.min(offset, total);
        int to = (int) Math.min(offset + size, total);
        List<KnowledgeBase> rows = visible.subList(from, to);

        Map<Long, Map<String, Object>> stats = new HashMap<>();
        for (Map<String, Object> row : docMapper.statsByKb()) {
            stats.put(((Number) row.get("kbId")).longValue(), row);
        }
        Map<Long, String> creators = creatorNames(rows);
        List<Map<String, Object>> list = rows.stream()
                .map(kb -> kbView(kb, viewer, stats.get(kb.getId()), creators.get(kb.getCreatedBy())))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        result.put("page", p);
        result.put("pageSize", size);
        return result;
    }

    /** 详情（含 canEdit，供前端控制上传/权限入口）。 */
    public Map<String, Object> detail(Long id, KnowledgePermissionService.Viewer viewer) {
        KnowledgeBase kb = permissionService.requireView(id, viewer);
        Map<String, Object> stat = null;
        for (Map<String, Object> row : docMapper.statsByKb()) {
            if (((Number) row.get("kbId")).longValue() == kb.getId()) {
                stat = row;
                break;
            }
        }
        String creator = creatorNames(List.of(kb)).get(kb.getCreatedBy());
        return kbView(kb, viewer, stat, creator);
    }

    private Map<String, Object> kbView(KnowledgeBase kb, KnowledgePermissionService.Viewer viewer,
                                       Map<String, Object> stat, String creatorName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", kb.getId());
        m.put("name", kb.getName());
        m.put("description", kb.getDescription());
        m.put("tenantId", kb.getTenantId());
        m.put("createdBy", kb.getCreatedBy());
        m.put("creatorName", creatorName);
        m.put("visibility", KnowledgePermissionService.visibilityOf(kb));
        // 解析设置：KB 自定义值 + 解析链最终生效值（前端"跟随默认/自定义"展示）
        Map<String, Object> kbCustom = new LinkedHashMap<>();
        if (kb.getParseConfig() != null && !kb.getParseConfig().isBlank()) {
            try {
                kbCustom = objectMapper.readValue(kb.getParseConfig(), Map.class);
            } catch (Exception ignored) { }
        }
        m.put("parseConfig", kbCustom);
        try {
            ParseOptions resolved = parseConfigResolver.resolve(kb, null);
            Map<String, Object> resolvedMap = new LinkedHashMap<>();
            resolvedMap.put("ocrEnabled", resolved.ocrEnabled());
            resolvedMap.put("ocrLanguages", resolved.ocrLanguages());
            resolvedMap.put("ocrDpi", resolved.ocrDpi());
            resolvedMap.put("ocrMaxPages", resolved.ocrMaxPages());
            resolvedMap.put("visionEnabled", resolved.visionEnabled());
            resolvedMap.put("visionModel", resolved.visionModel());
            resolvedMap.put("visionMaxImages", resolved.visionMaxImages());
            m.put("parseResolved", resolvedMap);
        } catch (Exception ignored) { }
        m.put("embeddingModel", kb.getEmbeddingModel());
        m.put("indexVersion", kb.getIndexVersion());
        m.put("graphEnabled", !Boolean.FALSE.equals(kb.getGraphEnabled()));
        m.put("createdAt", kb.getCreatedAt());
        m.put("documentCount", stat == null ? 0 : ((Number) stat.get("documentCount")).longValue());
        m.put("chunkCount", stat == null ? 0 : ((Number) stat.get("chunkCount")).longValue());
        m.put("canEdit", permissionService.canEdit(kb, viewer));
        return m;
    }

    private Map<Long, String> creatorNames(List<KnowledgeBase> kbs) {
        List<Long> ids = kbs.stream().map(KnowledgeBase::getCreatedBy)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        Map<Long, String> names = new HashMap<>();
        for (SysUser u : userMapper.selectBatchIds(ids)) {
            names.put(u.getId(), u.getDisplayName() == null || u.getDisplayName().isBlank()
                    ? u.getUsername() : u.getDisplayName());
        }
        return names;
    }

    /** 被哪些在用 Agent 引用（删除前提示，需求 3.6.3(6)）。 */
    public Map<String, Object> references(Long id, KnowledgePermissionService.Viewer viewer) {
        permissionService.requireEdit(id, viewer);
        List<String> names = referencingAgents(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agents", names);
        result.put("count", names.size());
        return result;
    }

    private List<String> referencingAgents(Long kbId) {
        List<AgentKnowledgeBase> bindings = agentKbMapper.selectList(new LambdaQueryWrapper<AgentKnowledgeBase>()
                .eq(AgentKnowledgeBase::getKnowledgeBaseId, kbId));
        List<Long> agentIds = bindings.stream().map(AgentKnowledgeBase::getAgentId).distinct().toList();
        if (agentIds.isEmpty()) return List.of();
        return agentRegistryMapper.selectBatchIds(agentIds).stream()
                .filter(a -> !"DELETED".equals(a.getStatus()))
                .map(AgentRegistry::getName)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 删除知识库（需求 3.6.3(6)）：被 Agent 引用时需 force 确认；
     * 级联删除文档/分段/授权 + MinIO 对象 + PgVector 向量（架构 6.5）。
     */
    @Transactional
    public void delete(Long id, boolean force, KnowledgePermissionService.Viewer viewer) {
        KnowledgeBase kb = permissionService.requireEdit(id, viewer);
        List<String> refs = referencingAgents(id);
        if (!refs.isEmpty() && !force) {
            throw new BusinessException(409, "知识库被 " + refs.size() + " 个 Agent 引用（"
                    + String.join("、", refs) + "），请确认后强制删除");
        }
        Long tenantId = kb.getTenantId();
        List<KnowledgeDocument> docs = docMapper.selectList(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getKnowledgeBaseId, id));
        for (KnowledgeDocument doc : docs) {
            deleteVectorsQuietly(tenantId, doc.getId());
            storage.remove(doc.getObjectName());
            deleteDocumentImages(doc);
        }
        try {
            vectorRepository.deleteByKnowledgeBase(tenantId, id);
        } catch (Exception e) {
            log.warn("删除知识库向量失败 kb={} err={}（数据库记录继续清理）", id, e.getMessage());
        }
        graphService.deleteByKnowledgeBase(id);
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getKnowledgeBaseId, id));
        docMapper.delete(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getKnowledgeBaseId, id));
        permissionService.deleteAll(id);
        kbMapper.deleteById(id);
        auditService.record(viewer.userId(), "kb.delete", "knowledge", String.valueOf(id), "SUCCESS",
                Map.of("name", kb.getName(), "documents", docs.size(), "forced", force));
        log.info("知识库已删除：id={} docs={} forced={}", id, docs.size(), force);
    }

    /** 删除单个文档：级联 MinIO 对象、PgVector 向量、分段；需编辑权限。 */
    @Transactional
    public void deleteDocument(Long kbId, Long docId, KnowledgePermissionService.Viewer viewer) {
        KnowledgeBase kb = permissionService.requireEdit(kbId, viewer);
        KnowledgeDocument doc = docMapper.selectById(docId);
        if (doc == null || !Objects.equals(doc.getKnowledgeBaseId(), kbId)) {
            throw new BusinessException(404, "文档不存在");
        }
        deleteVectorsQuietly(kb.getTenantId(), docId);
        storage.remove(doc.getObjectName());
        deleteDocumentImages(doc);
        graphService.deleteByDocument(kbId, docId);
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getDocumentId, docId));
        docMapper.deleteById(docId);
        auditService.record(viewer.userId(), "kb.document.delete", "knowledge_document",
                String.valueOf(docId), "SUCCESS", Map.of("kbId", kbId, "fileName", doc.getFileName()));
        log.info("文档已删除：kb={} doc={} file={}", kbId, docId, doc.getFileName());
    }

    /**
     * 解析失败重试（需求 3.6.3(2)）：仅 FAILED 可重试，默认上限 3 次；
     * 重试前清理上次失败可能残留的分段/向量，原文件保留。
     */
    public Map<String, Object> retryDocument(Long kbId, Long docId,
                                             KnowledgePermissionService.Viewer viewer) {
        KnowledgeBase kb = permissionService.requireEdit(kbId, viewer);
        KnowledgeDocument doc = docMapper.selectById(docId);
        if (doc == null || !Objects.equals(doc.getKnowledgeBaseId(), kbId)) {
            throw new BusinessException(404, "文档不存在");
        }
        if (!"FAILED".equals(doc.getStatus())) {
            throw new BusinessException(400, "仅解析失败的文档可重试");
        }
        int used = doc.getRetryTimes() == null ? 0 : doc.getRetryTimes();
        if (used >= maxParseRetries) {
            throw new BusinessException(400, "重试次数已达上限（" + maxParseRetries + " 次），需人工介入");
        }
        int updated = docMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getId, docId)
                .eq(KnowledgeDocument::getStatus, "FAILED")
                .set(KnowledgeDocument::getStatus, "PENDING")
                .set(KnowledgeDocument::getErrorMessage, null)
                .setSql("retry_times = retry_times + 1"));
        if (updated == 0) {
            throw new BusinessException(409, "文档状态已变化，请刷新后重试");
        }
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getDocumentId, docId));
        deleteVectorsQuietly(kb.getTenantId(), docId);
        deleteDocumentImages(doc);
        triggerParse(kb.getTenantId(), docId);
        auditService.record(viewer.userId(), "kb.document.retry", "knowledge_document",
                String.valueOf(docId), "SUCCESS", Map.of("kbId", kbId, "retryTimes", used + 1));
        log.info("文档重试解析：kb={} doc={} retry={}/{}", kbId, docId, used + 1, maxParseRetries);
        return Map.of("documentId", docId, "status", "PENDING", "retryTimes", used + 1);
    }

    /** 申请直传：返回预签名 PUT URL 与登记的 objectName（架构 3.8 路径规范）；需编辑权限。
     * documentId 非空表示重新上传覆盖：仅允许已完成/失败文档，解析中拒绝。 */
    public Map<String, Object> requestUpload(Long kbId, String fileName, Long sizeBytes,
                                             Long documentId, KnowledgePermissionService.Viewer viewer) {
        permissionService.requireEdit(kbId, viewer);
        if (sizeBytes != null && sizeBytes > 0) quotaService.checkStorage(sizeBytes);
        if (documentId != null) {
            KnowledgeDocument old = docMapper.selectById(documentId);
            if (old == null || !Objects.equals(old.getKnowledgeBaseId(), kbId)) {
                throw new BusinessException(404, "文档不存在");
            }
            if (!"DONE".equals(old.getStatus()) && !"FAILED".equals(old.getStatus())) {
                throw new BusinessException(409, "文档解析中，暂不能覆盖上传");
            }
        }
        Long tenantId = TenantContext.getTenantId();
        String safeName = safeFileName(fileName);
        String objectName = tenantId + "/knowledge/" + kbId + "/"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "/" + safeName;
        String uploadUrl = storage.presignPut(objectName);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uploadUrl", uploadUrl);
        result.put("objectName", objectName);
        if (documentId != null) result.put("documentId", documentId);
        return result;
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    /** 文档级 PDF 密码落库前 AES-GCM 加密；密码不进入日志或知识库级配置。 */
    private String protectPdfPassword(String json) {
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

    private String safeFileName(String fileName) {
        String name = fileName == null ? "file" : fileName.trim();
        name = name.replaceAll("[\\\\/\r\n]", "_");
        if (name.isBlank()) name = "file";
        return name.length() > 128 ? name.substring(name.length() - 128) : name;
    }

    /** 回调：校验对象存在 + 路径前缀一致（防伪造，需求文档 7.2）→ 登记 → 异步解析；需编辑权限。
     *  parseConfigJson：文档级解析配置覆盖（上传时选择，可空=跟随知识库）。 */
    public Map<String, Object> callback(Long kbId, String objectName, Long documentId,
                                        String parseConfigJson, KnowledgePermissionService.Viewer viewer) {
        return callback(kbId, objectName, documentId, parseConfigJson, null, null, viewer);
    }

    public Map<String, Object> callback(Long kbId, String objectName, Long documentId,
                                        String parseConfigJson, Instant effectiveFrom, Instant effectiveTo,
                                        KnowledgePermissionService.Viewer viewer) {
        permissionService.requireEdit(kbId, viewer);
        if (effectiveFrom != null && effectiveTo != null && effectiveFrom.isAfter(effectiveTo)) {
            throw new BusinessException(400, "内容生效开始时间不能晚于结束时间");
        }
        String protectedConfig = protectPdfPassword(parseConfigJson);
        Long tenantId = TenantContext.getTenantId();
        String expectedPrefix = tenantId + "/knowledge/" + kbId + "/";
        if (objectName == null || !objectName.startsWith(expectedPrefix)) {
            throw new BusinessException(403, "objectName 与登记路径不一致");
        }
        if (!storage.exists(objectName)) {
            throw new BusinessException(400, "文件尚未上传成功（MinIO 中不存在）");
        }
        String fileName = objectName.substring(objectName.lastIndexOf('/') + 1);
        if (documentId != null) {
            KnowledgeDocument doc = docMapper.selectById(documentId);
            if (doc == null || !Objects.equals(doc.getKnowledgeBaseId(), kbId)) {
                throw new BusinessException(404, "文档不存在");
            }
            if (!"DONE".equals(doc.getStatus()) && !"FAILED".equals(doc.getStatus())) {
                throw new BusinessException(409, "文档解析中，暂不能覆盖上传");
            }
            cleanupPartial(doc);
            deleteDocumentImages(doc);
            String oldObject = doc.getObjectName();
            doc.setFileName(fileName);
            doc.setObjectName(objectName);
            doc.setStatus("PENDING");
            doc.setChunkCount(0);
            doc.setParseConfig(protectedConfig);
            if (effectiveFrom != null) doc.setEffectiveFrom(toLocalDateTime(effectiveFrom));
            if (effectiveTo != null) doc.setEffectiveTo(toLocalDateTime(effectiveTo));
            doc.setSizeBytes(storage.statSize(objectName));
            doc.setRetryTimes(0);
            doc.setErrorMessage(null);
            docMapper.updateById(doc);
            if (oldObject != null && !oldObject.equals(objectName)) {
                storage.remove(oldObject);
            }
            triggerParse(tenantId, doc.getId());
            auditService.record(viewer.userId(), "kb.document.reupload", "knowledge_document",
                    String.valueOf(doc.getId()), "SUCCESS",
                    Map.of("kbId", kbId, "fileName", fileName));
            log.info("文档覆盖上传：kb={} doc={} file={}", kbId, doc.getId(), fileName);
            return Map.of("documentId", doc.getId(), "status", doc.getStatus(), "overwritten", true);
        }
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setKnowledgeBaseId(kbId);
        doc.setFileName(fileName);
        doc.setObjectName(objectName);
        doc.setStatus("PENDING");
        doc.setChunkCount(0);
        doc.setParseConfig(protectedConfig);
        doc.setEffectiveFrom(effectiveFrom == null ? null : toLocalDateTime(effectiveFrom));
        doc.setEffectiveTo(effectiveTo == null ? null : toLocalDateTime(effectiveTo));
        doc.setSizeBytes(storage.statSize(objectName));
        doc.setRetryTimes(0);
        doc.setTenantId(tenantId);
        doc.setCreatedAt(LocalDateTime.now());
        docMapper.insert(doc);

        triggerParse(tenantId, doc.getId());
        return Map.of("documentId", doc.getId(), "status", doc.getStatus());
    }

    /** URL 导入：抓取网页/PDF/文本并存入对象存储，复用回调登记与异步解析。 */
    public Map<String, Object> importUrl(Long kbId, String rawUrl, KnowledgePermissionService.Viewer viewer) {
        permissionService.requireEdit(kbId, viewer);
        if (rawUrl == null || rawUrl.isBlank()) throw new BusinessException(400, "URL 不能为空");
        String url = rawUrl.trim();
        networkPolicy.validateHttpUrl(url, "URL");
        Fetched fetched;
        try {
            fetched = fetch(url, 0);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "URL 抓取失败：" + rootMessage(e));
        }
        if (fetched.bytes.length > importMaxBytes) {
            throw new BusinessException(413, "URL 内容超过 " + (importMaxBytes / 1024 / 1024) + "MB 限制");
        }
        quotaService.checkStorage(fetched.bytes.length);
        Long tenantId = TenantContext.getTenantId();
        String fileName = fileNameFor(fetched.finalUrl, fetched.contentType);
        String objectName = tenantId + "/knowledge/" + kbId + "/"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "/" + fileName;
        storage.put(objectName, fetched.bytes, fetched.contentType);
        Map<String, Object> result = new LinkedHashMap<>(callback(kbId, objectName, null, null, viewer));
        result.put("sourceUrl", fetched.finalUrl);
        result.put("fileName", fileName);
        auditService.record(viewer.userId(), "kb.document.url_import", "knowledge_document",
                String.valueOf(result.get("documentId")), "SUCCESS",
                Map.of("kbId", kbId, "url", fetched.finalUrl, "fileName", fileName));
        return result;
    }

    private record Fetched(byte[] bytes, String contentType, String finalUrl) {
    }

    private Fetched fetch(String url, int depth) throws Exception {
        if (depth > 3) throw new BusinessException(400, "URL 重定向次数过多");
        networkPolicy.validateHttpUrl(url, "URL");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "AI-Agent-Platform/1.0")
                .header("Accept", "text/html,text/plain,application/pdf,application/json,text/csv;q=0.9,*/*;q=0.1")
                .GET().build();
        // 使用流式响应，避免在检查大小前把超大文件完整读入内存（诚实的 Content-Length 仅作快速失败）。
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            closeQuietly(response.body());
            String location = response.headers().firstValue("location").orElse(null);
            if (location == null || location.isBlank()) {
                throw new BusinessException(502, "URL 返回重定向但缺少 Location");
            }
            String next = URI.create(url).resolve(location).toString();
            return fetch(next, depth + 1);
        }
        if (status < 200 || status >= 300) {
            closeQuietly(response.body());
            throw new BusinessException(502, "URL 抓取失败：HTTP " + status);
        }
        long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1);
        if (contentLength > importMaxBytes) {
            closeQuietly(response.body());
            throw tooLarge();
        }
        String contentType = response.headers().firstValue("content-type")
                .orElse("text/html").toLowerCase().split(";")[0].trim();
        if (!(contentType.startsWith("text/") || contentType.equals("application/pdf")
                || contentType.equals("application/json")
                || contentType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))) {
            closeQuietly(response.body());
            throw new BusinessException(400, "URL 内容类型不支持：" + contentType);
        }
        try (InputStream body = response.body()) {
            return new Fetched(readBounded(body), contentType, url);
        }
    }

    /** 最多读取 importMaxBytes + 1 字节：超过上限立即中止，不把剩余响应体载入内存。 */
    private byte[] readBounded(InputStream body) throws Exception {
        int limit = Math.max(1, importMaxBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = body.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw tooLarge();
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private BusinessException tooLarge() {
        return new BusinessException(413,
                "URL 内容超过 " + (importMaxBytes / 1024 / 1024) + "MB 限制");
    }

    private void closeQuietly(InputStream body) {
        if (body == null) return;
        try {
            body.close();
        } catch (Exception ignored) {
        }
    }

    private String fileNameFor(String url, String contentType) {
        String name = "url-import";
        try {
            String path = URI.create(url).getPath();
            if (path != null && !path.isBlank()) {
                String last = path.substring(path.lastIndexOf('/') + 1);
                if (!last.isBlank()) name = last;
            }
        } catch (Exception e) {
            log.debug("URL 文件名解析失败：{}", e.getMessage());
        }
        name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (name.length() > 80) name = name.substring(name.length() - 80);
        String lower = name.toLowerCase();
        boolean hasKnownExt = lower.matches(".*\\.(html?|pdf|txt|md|csv|json|docx)$");
        if (hasKnownExt) return name;
        String ext = switch (contentType) {
            case "application/pdf" -> ".pdf";
            case "application/json" -> ".json";
            case "text/csv" -> ".csv";
            case "text/markdown" -> ".md";
            case "text/html" -> ".html";
            default -> ".txt";
        };
        return name + ext;
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    /** 异步触发解析（专用线程池 + 还原租户上下文）。 */
    private void triggerParse(Long tenantId, Long documentId) {
        try {
            documentParseExecutor.execute(() -> {
                try {
                    TenantContext.run(tenantId, () -> doParse(documentId));
                } catch (Exception e) {
                    log.warn("文档解析调度异常：doc={} err={}", documentId, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("文档解析提交失败：doc={} err={}", documentId, e.getMessage());
        }
    }

    private void doParse(Long documentId) {
        KnowledgeDocument doc = docMapper.selectById(documentId);
        if (doc == null) return;
        updateStatus(doc, "PARSING", null, null);
        int attempts = 1 + Math.max(autoParseRetries, 0);
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                parseOnce(doc);
                return;
            } catch (Exception e) {
                Throwable root = e;
                while (root.getCause() != null) root = root.getCause();
                log.warn("文档解析失败（第 {}/{} 次）doc={} err={}", attempt + 1, attempts,
                        documentId, root.getMessage());
                cleanupPartial(doc);
                if (attempt == attempts - 1) {
                    updateStatus(doc, "FAILED", null, root.getMessage());
                    return;
                }
                try {
                    Thread.sleep(600L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    updateStatus(doc, "FAILED", null, "解析被中断");
                    return;
                }
                updateStatus(doc, "PARSING", null, null);
            }
        }
    }

    /** 单次解析：上传对象 → 分段 → 嵌入 → 向量落库。失败时由 doParse 清理残留并重试。 */
    private void parseOnce(KnowledgeDocument doc) throws Exception {
        byte[] bytes = storage.get(doc.getObjectName());
        KnowledgeBase kb = kbMapper.selectById(doc.getKnowledgeBaseId());
        KnowledgePermissionService.Acl acl = kb == null
                ? new KnowledgePermissionService.Acl(List.of(), true) : permissionService.aclOf(kb);
        String embeddingModel = kb == null || kb.getEmbeddingModel() == null
                || kb.getEmbeddingModel().isBlank()
                ? embeddingService.model() : kb.getEmbeddingModel();
        int embeddingDimensions = embeddingService.dimension(embeddingModel);
        // 四级解析配置：env → 平台 → 租户 → 知识库 → 文档（上传时选择）
        ParseOptions parseOptions = parseConfigResolver.resolve(kb, doc.getParseConfig());
        List<DocumentParser.ParsedChunk> chunks = new ArrayList<>(
                parser.parseWithParents(doc.getFileName(), bytes, parseOptions));
        if (parseOptions.visionEnabled()) {
            int described = 0;
            int maxImages = Math.max(1, Math.min(parseOptions.visionMaxImages(), 10));
            for (DocumentParser.ExtractedImage image : parser.extractImages(doc.getFileName(), bytes)) {
                if (described >= maxImages) break;
                String description = imageUnderstanding.describe(image.data(), image.extension(), parseOptions);
                if (description == null || description.isBlank()) continue;
                String content = "【图片说明】" + description.trim();
                if (content.length() > 500) content = content.substring(0, 500);
                chunks.add(new DocumentParser.ParsedChunk(content, chunks.size(), content));
                if (description.contains("图") || description.toLowerCase().contains("chart")) {
                    try {
                        com.fasterxml.jackson.databind.JsonNode chart =
                                imageUnderstanding.extractChartData(image.data(), image.extension(), parseOptions);
                        if (chart != null) {
                            String chartText = "【图表数据·模型识别，需人工核对】" + objectMapper.writeValueAsString(chart);
                            if (chartText.length() > 1200) chartText = chartText.substring(0, 1200);
                            chunks.add(new DocumentParser.ParsedChunk(chartText, chunks.size(), chartText));
                        }
                    } catch (Exception e) {
                        log.debug("图表结构化分段跳过 doc={}：{}", doc.getId(), e.getMessage());
                    }
                }
                described++;
            }
            if (described > 0) log.info("文档图片多模态分段：doc={} images={}", doc.getId(), described);
        }
        float[][] vectors = embeddingService.embedBatch(
                chunks.stream().map(DocumentParser.ParsedChunk::content).toList(), embeddingModel);
        // 业务有效期优先；未配置时回退文档入库时间，而不是向量写入时间。
        LocalDateTime effectiveFrom = doc.getEffectiveFrom() != null
                ? doc.getEffectiveFrom() : doc.getCreatedAt();
        if (effectiveFrom == null) effectiveFrom = LocalDateTime.now();
        LocalDateTime effectiveTo = doc.getEffectiveTo();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            DocumentParser.ParsedChunk parsed = chunks.get(i);
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setDocumentId(doc.getId());
            chunk.setKnowledgeBaseId(doc.getKnowledgeBaseId());
            chunk.setChunkIndex(i);
            chunk.setParentIndex(parsed.parentIndex());
            chunk.setParentContent(parsed.parentContent());
            chunk.setContent(parsed.content());
            chunk.setAllowedUsers(usersJson(acl.allowedUsers()));
            chunk.setTenantVisible(acl.tenantWide() ? 1 : 0);
            chunk.setTenantId(doc.getTenantId());
            chunk.setCreatedAt(LocalDateTime.now());
            chunkMapper.insert(chunk);

            Map<String, Object> row = new HashMap<>();
            row.put("content", parsed.content());
            row.put("embedding", vectors[i]);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("knowledge_base_id", doc.getKnowledgeBaseId());
            metadata.put("document_id", doc.getId());
            metadata.put("chunk_id", chunk.getId());
            metadata.put("chunk_index", i);
            metadata.put("parent_index", parsed.parentIndex());
            metadata.put("embedding_model", embeddingModel);
            metadata.put("allowed_users", acl.allowedUsers().stream().map(String::valueOf).toList());
            metadata.put("tenant_visible", acl.tenantWide());
            metadata.put("file_name", doc.getFileName());
            metadata.put("document_created_at", doc.getCreatedAt() == null ? null : doc.getCreatedAt().toString());
            metadata.put("effective_from", effectiveFrom.toString());
            if (effectiveTo != null) metadata.put("effective_to", effectiveTo.toString());
            row.put("metadata", objectMapper.writeValueAsString(metadata));
            row.put("effective_from", java.sql.Timestamp.valueOf(effectiveFrom));
            row.put("effective_to", effectiveTo == null ? null : java.sql.Timestamp.valueOf(effectiveTo));
            rows.add(row);
        }
        vectorRepository.saveBatch(doc.getTenantId(), rows, embeddingDimensions);
        storeDocumentImages(doc, bytes);
        updateStatus(doc, "DONE", chunks.size(), null);
        recordEmbeddingUsage(doc, chunks.size(), embeddingModel);
        // 解析完成后自动生成知识图谱（异步，失败不影响文档状态；可在系统配置关闭）
        graphService.autoExtractAfterParse(doc.getTenantId(), doc.getKnowledgeBaseId(), doc.getId());
        log.info("文档解析完成：doc={} chunks={}", doc.getId(), chunks.size());
    }

    /** 重新解析（评审设计 5）：DONE/FAILED 可重解析；清理旧分段/向量/图片后重新触发（沿用文档级解析配置）。 */
    @Transactional
    public Map<String, Object> reparse(Long kbId, Long documentId, KnowledgePermissionService.Viewer viewer) {
        permissionService.requireEdit(kbId, viewer);
        KnowledgeDocument doc = docMapper.selectById(documentId);
        if (doc == null || !doc.getKnowledgeBaseId().equals(kbId)) {
            throw new BusinessException(404, "文档不存在");
        }
        if (!"DONE".equals(doc.getStatus()) && !"FAILED".equals(doc.getStatus())) {
            throw new BusinessException(400, "仅已完成/失败的文档可重新解析（当前：" + doc.getStatus() + "）");
        }
        cleanupPartial(doc);
        deleteDocumentImages(doc);
        KnowledgeDocument patch = new KnowledgeDocument();
        patch.setId(documentId);
        patch.setStatus("PENDING");
        patch.setChunkCount(0);
        docMapper.updateById(patch);
        triggerParse(doc.getTenantId(), documentId);
        auditService.record(viewer.userId(), "knowledge.document.reparse", "knowledge_document",
                String.valueOf(documentId), "SUCCESS", Map.of("kbId", kbId));
        return Map.of("documentId", documentId, "status", "PENDING");
    }

    /** 编辑文档内容有效期；同步更新向量表有效期，避免要求重新解析。 */
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

    /** 补偿清理：重试前删除可能已写入的部分分段与向量，并清掉该文档贡献的自动图谱。 */
    private void cleanupPartial(KnowledgeDocument doc) {
        try {
            chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunk>()
                    .eq(KnowledgeChunk::getDocumentId, doc.getId()));
        } catch (Exception e) {
            log.warn("清理失败分段失败 doc={}：{}", doc.getId(), e.getMessage());
        }
        deleteVectorsQuietly(doc.getTenantId(), doc.getId());
        try {
            graphService.deleteByDocument(doc.getKnowledgeBaseId(), doc.getId());
        } catch (Exception e) {
            log.warn("清理文档自动图谱失败 doc={}：{}", doc.getId(), e.getMessage());
        }
    }

    /** 检索测试（需求文档 3.6(4)）：query 嵌入 → PgVector topK + 阈值过滤；需可见权限。 */
    public List<Map<String, Object>> search(Long kbId, String query, Integer topK, Double threshold,
                                            String mode, KnowledgePermissionService.Viewer viewer) {
        return search(kbId, query, topK, threshold, mode, null, null, viewer);
    }

    /** 带内容有效期范围的检索：在 PgVector SQL 层做区间重叠过滤，避免先取 topK 后截断有效数据。 */
    public List<Map<String, Object>> search(Long kbId, String query, Integer topK, Double threshold,
                                            String mode, Instant effectiveAfter, Instant effectiveBefore,
                                            KnowledgePermissionService.Viewer viewer) {
        permissionService.requireView(kbId, viewer);
        Long tenantId = TenantContext.getTenantId();
        if (effectiveAfter != null || effectiveBefore != null) {
            if (effectiveAfter != null && effectiveBefore != null && effectiveAfter.isAfter(effectiveBefore)) {
                throw new BusinessException(400, "生效开始时间不能晚于生效结束时间");
            }
            KnowledgeBase kb = kbMapper.selectById(kbId);
            if (kb == null) throw new BusinessException(404, "知识库不存在");
            String model = kb.getEmbeddingModel() == null || kb.getEmbeddingModel().isBlank()
                    ? embeddingService.model() : kb.getEmbeddingModel().trim();
            if (!embeddingService.supports(model)) model = embeddingService.model();
            float[] vector = embeddingService.embedOne(query, model);
            int dimensions = embeddingService.dimension(model);
            int limit = Math.max(1, Math.min(topK == null ? 5 : topK, 20));
            double scoreThreshold = Math.max(0, Math.min(threshold == null ? 0.5 : threshold, 1));
            return vectorRepository.search(tenantId, vector, kbId, limit, scoreThreshold, dimensions,
                    viewer.userId(), permissionService.tenantWide(viewer), effectiveAfter, effectiveBefore);
        }
        Map<String, Object> retrievalConfig = mode == null || mode.isBlank()
                ? Map.of() : Map.of("mode", mode);
        List<Map<String, Object>> hits = new ArrayList<>(hybridRetrieval.search(tenantId, query, kbId,
                topK == null ? 5 : topK,
                threshold == null ? 0.5 : threshold, retrievalConfig,
                viewer.userId(), permissionService.tenantWide(viewer)).hits());
        Set<Long> existing = new java.util.HashSet<>();
        List<String> contents = new ArrayList<>();
        for (Map<String, Object> hit : hits) {
            contents.add(String.valueOf(hit.getOrDefault("content", "")));
            try {
                if (hit.get("metadata") instanceof String meta && !meta.isBlank()) {
                    JsonNode node = objectMapper.readTree(meta);
                    if (node.has("chunk_id")) existing.add(node.get("chunk_id").asLong());
                }
            } catch (Exception ignored) { }
        }
        try {
            hits.addAll(graphService.expandChunks(kbId, query, contents, existing, viewer));
        } catch (Exception e) {
            log.warn("知识图谱检索扩展失败（跳过）kb={} err={}", kbId, e.getMessage());
        }
        return hits;
    }

    /** 文档分段浏览（架构 4.8 KnowledgeDetail/ChunkPanel 数据源）；需可见权限。 */
    public Map<String, Object> chunks(Long kbId, Long documentId, long page, long pageSize,
                                      KnowledgePermissionService.Viewer viewer) {
        permissionService.requireView(kbId, viewer);
        long p = Math.max(page, 1);
        long size = Math.min(Math.max(pageSize, 1), 100);
        Page<KnowledgeChunk> result = chunkMapper.selectPage(new Page<>(p, size),
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getKnowledgeBaseId, kbId)
                        .eq(KnowledgeChunk::getDocumentId, documentId)
                        .orderByAsc(KnowledgeChunk::getChunkIndex));
        List<Map<String, Object>> list = result.getRecords().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("chunkIndex", c.getChunkIndex());
            m.put("content", c.getContent());
            m.put("allowedUsers", parseJson(c.getAllowedUsers()));
            m.put("tenantVisible", c.getTenantVisible() == null || c.getTenantVisible() == 1);
            m.put("reviewStatus", c.getReviewStatus());
            m.put("reviewRequired", isChartChunk(c.getContent())
                    && !"REVIEWED".equals(c.getReviewStatus()));
            m.put("reviewedAt", c.getReviewedAt());
            m.put("reviewedBy", c.getReviewedBy());
            return m;
        }).toList();
        return Map.of("list", list, "total", result.getTotal(), "page", p, "pageSize", size);
    }

    /** 单 chunk 独立授权：更新 MySQL 分段与 PgVector metadata；需知识库编辑权限。 */
    public Map<String, Object> updateChunkAcl(Long kbId, Long chunkId, List<Long> allowedUsers,
                                               boolean tenantVisible,
                                               KnowledgePermissionService.Viewer viewer) {
        KnowledgeBase kb = permissionService.requireEdit(kbId, viewer);
        KnowledgeChunk chunk = chunkMapper.selectById(chunkId);
        if (chunk == null || !Objects.equals(chunk.getKnowledgeBaseId(), kbId)) {
            throw new BusinessException(404, "分段不存在");
        }
        List<Long> users = allowedUsers == null ? new ArrayList<>() : new ArrayList<>(allowedUsers);
        permissionService.validateAclUsers(kb.getTenantId(), users);
        if (chunk.getDocumentId() == null) throw new BusinessException(400, "分段缺少文档信息");
        String usersJson = users.stream().map(String::valueOf).map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        chunkMapper.update(null, new LambdaUpdateWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getId, chunkId)
                .set(KnowledgeChunk::getAllowedUsers, usersJson)
                .set(KnowledgeChunk::getTenantVisible, tenantVisible ? 1 : 0));
        vectorRepository.updateAclForChunk(kb.getTenantId(), kbId, chunkId, users, tenantVisible);
        auditService.record(viewer.userId(), "knowledge.chunk_acl", "knowledge_chunk",
                String.valueOf(chunkId), "SUCCESS",
                Map.of("knowledgeBaseId", kbId, "tenantVisible", tenantVisible,
                        "allowedUsers", users));
        return Map.of("id", chunkId, "allowedUsers", users, "tenantVisible", tenantVisible);
    }

    /** 图表分段人工复核：标记已核对/撤销核对；可选修正图表内容并同步向量。 */
    public Map<String, Object> updateChunkReview(Long kbId, Long chunkId, boolean reviewed,
                                                  KnowledgePermissionService.Viewer viewer) {
        return updateChunkReview(kbId, chunkId, reviewed, null, viewer);
    }

    public Map<String, Object> updateChunkReview(Long kbId, Long chunkId, boolean reviewed,
                                                  String content,
                                                  KnowledgePermissionService.Viewer viewer) {
        KnowledgeBase kb = permissionService.requireEdit(kbId, viewer);
        KnowledgeChunk chunk = chunkMapper.selectById(chunkId);
        if (chunk == null || !Objects.equals(chunk.getKnowledgeBaseId(), kbId)) {
            throw new BusinessException(404, "分段不存在");
        }
        if (!isChartChunk(chunk.getContent())) {
            throw new BusinessException(400, "仅图表数据分段支持人工复核");
        }
        String updatedContent = chunk.getContent();
        boolean contentChanged = content != null && !content.isBlank()
                && !content.equals(chunk.getContent());
        if (contentChanged) {
            if (!isChartChunk(content)) {
                throw new BusinessException(400, "图表内容必须保留【图表数据】标记");
            }
            if (content.length() > 4000) {
                throw new BusinessException(400, "图表内容过长（最多 4000 字）");
            }
            updatedContent = content;
        }
        String status = reviewed ? "REVIEWED" : "PENDING";
        LocalDateTime reviewedAt = reviewed ? LocalDateTime.now() : null;
        Long reviewedBy = reviewed ? viewer.userId() : null;
        LambdaUpdateWrapper<KnowledgeChunk> update = new LambdaUpdateWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getId, chunkId)
                .set(KnowledgeChunk::getReviewStatus, status)
                .set(KnowledgeChunk::getReviewedAt, reviewedAt)
                .set(KnowledgeChunk::getReviewedBy, reviewedBy);
        if (contentChanged) update.set(KnowledgeChunk::getContent, updatedContent);
        chunkMapper.update(null, update);
        if (contentChanged) {
            String model = kb.getEmbeddingModel() == null || kb.getEmbeddingModel().isBlank()
                    ? embeddingService.model() : kb.getEmbeddingModel().trim();
            int dimensions = embeddingService.dimension(model);
            float[] vector = embeddingService.embedOne(updatedContent, model);
            try {
                vectorRepository.updateContentForChunk(kb.getTenantId(), kbId, chunkId,
                        updatedContent, vector, dimensions);
            } catch (Exception e) {
                log.warn("图表内容向量更新失败（MySQL 已更新）kb={} chunk={} err={}",
                        kbId, chunkId, e.getMessage());
                throw new BusinessException(500, "图表内容已保存，但向量更新失败，请重新解析文档");
            }
        }
        auditService.record(viewer.userId(), "knowledge.chunk_review", "knowledge_chunk",
                String.valueOf(chunkId), "SUCCESS",
                Map.of("knowledgeBaseId", kbId, "reviewed", reviewed,
                        "contentChanged", contentChanged));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", chunkId);
        result.put("reviewStatus", status);
        result.put("reviewedAt", reviewedAt);
        result.put("reviewedBy", reviewedBy);
        result.put("content", updatedContent);
        result.put("contentChanged", contentChanged);
        return result;
    }

    private static boolean isChartChunk(String content) {
        return content != null && content.startsWith(CHART_CHUNK_MARKER);
    }

    public List<KnowledgeDocument> documents(Long kbId, KnowledgePermissionService.Viewer viewer) {
        permissionService.requireView(kbId, viewer);
        return docMapper.selectList(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getKnowledgeBaseId, kbId)
                .orderByDesc(KnowledgeDocument::getCreatedAt));
    }

    private void deleteVectorsQuietly(Long tenantId, Long documentId) {
        try {
            vectorRepository.deleteByDocument(tenantId, documentId);
        } catch (Exception e) {
            log.warn("删除文档向量失败 doc={} err={}（数据库记录继续清理）", documentId, e.getMessage());
        }
    }

    private Object parseJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return objectMapper.readValue(raw, Object.class);
        } catch (Exception e) {
            return raw;
        }
    }

    private String toJsonQuiet(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String usersJson(List<Long> users) {
        // MySQL JSON_CONTAINS 与 PgVector jsonb_array_elements_text 都按字符串匹配 userId
        return users.stream().map(String::valueOf).map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private void storeDocumentImages(KnowledgeDocument doc, byte[] bytes) {
        try {
            deleteDocumentImages(doc);
            List<DocumentParser.ExtractedImage> images = parser.extractImages(doc.getFileName(), bytes);
            int index = 1;
            for (DocumentParser.ExtractedImage image : images) {
                String ext = image.extension() == null || image.extension().isBlank()
                        ? "png" : image.extension().toLowerCase(Locale.ROOT);
                if (ext.startsWith(".")) ext = ext.substring(1);
                String objectName = doc.getTenantId() + "/knowledge/" + doc.getKnowledgeBaseId() + "/"
                        + doc.getId() + "/images/" + UUID.randomUUID().toString().replace("-", "")
                        .substring(0, 12) + "." + ext;
                String mime = "image/" + ("jpg".equals(ext) ? "jpeg" : ext);
                storage.put(objectName, image.data(), mime);
                KnowledgeDocumentImage row = new KnowledgeDocumentImage();
                row.setTenantId(doc.getTenantId());
                row.setDocumentId(doc.getId());
                row.setObjectName(objectName);
                row.setFileName("image-" + index++ + "." + ext);
                row.setMimeType(mime);
                row.setSizeBytes((long) image.data().length);
                row.setCreatedAt(LocalDateTime.now());
                documentImageMapper.insert(row);
            }
        } catch (Exception e) {
            log.warn("文档图片保存失败（不影响正文）doc={}：{}", doc.getId(), e.getMessage());
        }
    }

    private void deleteDocumentImages(KnowledgeDocument doc) {
        if (doc == null || doc.getId() == null) return;
        List<KnowledgeDocumentImage> rows = documentImageMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentImage>()
                        .eq(KnowledgeDocumentImage::getDocumentId, doc.getId()));
        for (KnowledgeDocumentImage row : rows) {
            storage.remove(row.getObjectName());
        }
        documentImageMapper.delete(new LambdaQueryWrapper<KnowledgeDocumentImage>()
                .eq(KnowledgeDocumentImage::getDocumentId, doc.getId()));
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

    private void updateStatus(KnowledgeDocument doc, String status, Integer chunkCount, String error) {
        KnowledgeDocument patch = new KnowledgeDocument();
        patch.setId(doc.getId());
        patch.setStatus(status);
        patch.setChunkCount(chunkCount);
        patch.setErrorMessage(error);
        docMapper.updateById(patch);
    }

    private void recordEmbeddingUsage(KnowledgeDocument doc, int chunkCount, String embeddingModel) {
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
