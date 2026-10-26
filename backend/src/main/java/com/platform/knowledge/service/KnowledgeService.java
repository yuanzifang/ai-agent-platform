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
import com.platform.knowledge.mapper.KnowledgeParentMapper;
import com.platform.knowledge.mapper.KnowledgeVectorOutboxMapper;
import com.platform.knowledge.entity.KnowledgeParent;
import com.platform.knowledge.entity.KnowledgeVectorOutbox;
import com.platform.system.entity.SysUser;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.service.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    private static final String CHART_CHUNK_PREFIX = "【图表数据·模型识别，需人工核对】";
    private static final int CHART_CHUNK_MAX = 4000;

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

    /** MySQL 主库 JdbcTemplate；测试环境未注入时回退逐条 MyBatis insert。 */
    @Autowired(required = false)
    @Qualifier("jdbcTemplate")
    private JdbcTemplate mysqlJdbcTemplate;

    /** 父块独立表；测试环境未注入时回退 chunk.parent_content。 */
    @Autowired(required = false)
    private KnowledgeParentMapper parentMapper;

    /** MySQL 本地事务：父块/分段/批量回退同成同败；PgVector 写入仍由 cleanupPartial 补偿。 */
    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;
    @Autowired(required = false)
    private KnowledgeVectorOutboxMapper vectorOutboxMapper;

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

    /** embedding 缓存保留天数；每小时最多清理一次，避免每次解析都扫表。 */
    @Value("${embedding.cache-ttl-days:30}")
    private int embeddingCacheTtlDays = 30;
    @Value("${embedding.cache-max-entries:100000}")
    private int embeddingCacheMaxEntries = 100000;
    private final java.util.concurrent.atomic.AtomicLong lastCacheCleanupAt =
            new java.util.concurrent.atomic.AtomicLong(0);

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
        for (KnowledgeDocument doc : docs) deleteParentsQuietly(doc.getId());
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
        deleteParentsQuietly(docId);
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
        deleteParentsQuietly(docId);
        deleteVectorsQuietly(kb.getTenantId(), docId);
        deleteDocumentImages(doc);
        triggerParse(kb.getTenantId(), docId);
        auditService.record(viewer.userId(), "kb.document.retry", "knowledge_document",
                String.valueOf(docId), "SUCCESS", Map.of("kbId", kbId, "retryTimes", used + 1));
        log.info("文档重试解析：kb={} doc={} retry={}/{}", kbId, docId, used + 1, maxParseRetries);
        return Map.of("documentId", docId, "status", "PENDING", "retryTimes", used + 1);
    }

    /** 批量重试本知识库的失败文档（受单文档重试上限约束；每批最多 50 篇）。 */
    public Map<String, Object> retryFailedDocuments(Long kbId, int limit,
                                                    KnowledgePermissionService.Viewer viewer) {
        permissionService.requireEdit(kbId, viewer);
        int max = Math.max(1, Math.min(limit, 50));
        List<KnowledgeDocument> docs = docMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getKnowledgeBaseId, kbId)
                        .eq(KnowledgeDocument::getStatus, "FAILED")
                        .orderByAsc(KnowledgeDocument::getCreatedAt)
                        .last("LIMIT " + max));
        int triggered = 0;
        int skipped = 0;
        if (docs != null) {
            for (KnowledgeDocument doc : docs) {
                try {
                    retryDocument(kbId, doc.getId(), viewer);
                    triggered++;
                } catch (Exception e) {
                    skipped++;
                    log.warn("批量重试文档失败 doc={} err={}", doc.getId(), e.getMessage());
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scanned", docs == null ? 0 : docs.size());
        result.put("triggered", triggered);
        result.put("skipped", skipped);
        return result;
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
        return importUrl(kbId, rawUrl, null, null, viewer);
    }

    public Map<String, Object> importUrl(Long kbId, String rawUrl, Instant effectiveFrom, Instant effectiveTo,
                                          KnowledgePermissionService.Viewer viewer) {
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
        Map<String, Object> result = new LinkedHashMap<>(callback(kbId, objectName, null, null,
                effectiveFrom, effectiveTo, viewer));
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

    /**
     * 回收长时间停在 PARSING 的文档：先条件更新为 FAILED，再清理 MySQL/PgVector 残留。
     * 多实例同时执行时，只有条件更新成功的实例会继续清理。
     */
    public int recoverStaleParsing(int staleMinutes, int limit) {
        return recoverStaleParsing(null, staleMinutes, limit);
    }

    /** 知识库维度的超时解析回收（手动触发时使用，权限校验后调用）。 */
    public int recoverStaleParsingForKb(Long kbId, int staleMinutes, int limit,
                                        KnowledgePermissionService.Viewer viewer) {
        permissionService.requireEdit(kbId, viewer);
        return recoverStaleParsing(kbId, staleMinutes, limit);
    }

    public int recoverStaleParsing(Long kbId, int staleMinutes, int limit) {
        int minutes = Math.max(1, staleMinutes);
        int max = Math.max(1, Math.min(limit, 100));
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(minutes);
        // 兼容 V86 之前的历史 PARSING 文档：没有 parse_started_at 时用 created_at 判断
        List<KnowledgeDocument> stale = docMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getStatus, "PARSING")
                        .eq(kbId != null, KnowledgeDocument::getKnowledgeBaseId, kbId)
                        .and(w -> w.lt(KnowledgeDocument::getParseStartedAt, cutoff)
                                .or()
                                .isNull(KnowledgeDocument::getParseStartedAt)
                                .lt(KnowledgeDocument::getCreatedAt, cutoff))
                        .orderByAsc(KnowledgeDocument::getCreatedAt)
                        .last("LIMIT " + max));
        if (stale == null || stale.isEmpty()) return 0;
        int recovered = 0;
        for (KnowledgeDocument doc : stale) {
            try {
                int claimed = docMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getId, doc.getId())
                        .eq(KnowledgeDocument::getStatus, "PARSING")
                        .set(KnowledgeDocument::getStatus, "FAILED")
                        .set(KnowledgeDocument::getChunkCount, 0)
                        .set(KnowledgeDocument::getParseStartedAt, null)
                        .set(KnowledgeDocument::getErrorMessage,
                                "解析超过 " + minutes + " 分钟未完成，已清理残留；请重试"));
                if (claimed == 0) continue;
                cleanupPartial(doc);
                recovered++;
                log.warn("回收超时解析文档：doc={} staleMinutes={}", doc.getId(), minutes);
            } catch (Exception e) {
                log.warn("回收超时解析文档失败 doc={} err={}", doc.getId(), e.getMessage());
            }
        }
        return recovered;
    }

    private void doParse(Long documentId) {
        KnowledgeDocument doc = docMapper.selectById(documentId);
        if (doc == null) return;
        updateStatus(doc, "PARSING", null, null);
        markOutboxPending(doc);
        storeParseWarning(doc, List.of());
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
                if (root instanceof BusinessException business && !isRetryable(business)) {
                    log.info("文档解析遇到确定性错误，不重试：doc={} code={} err={}",
                            documentId, business.getCode(), business.getMessage());
                    updateStatus(doc, "FAILED", null, business.getMessage());
                    markOutboxFailed(doc, business.getMessage());
                    return;
                }
                if (attempt == attempts - 1) {
                    updateStatus(doc, "FAILED", null, root.getMessage());
                    markOutboxFailed(doc, root.getMessage());
                    return;
                }
                try {
                    Thread.sleep(600L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    updateStatus(doc, "FAILED", null, "解析被中断");
                    markOutboxFailed(doc, "解析被中断");
                    return;
                }
                updateStatus(doc, "PARSING", null, null);
            }
        }
    }

    /** 400/401/403/404/422 等确定性错误重试也不会成功；只重试 5xx 与限流。 */
    private boolean isRetryable(BusinessException e) {
        int code = e.getCode();
        return code >= 500 || code == 429;
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
        List<String> parseWarnings = new ArrayList<>();
        String sourceSha = sha256(bytes);
        // 四级解析配置：env → 平台 → 租户 → 知识库 → 文档（上传时选择）
        ParseOptions parseOptions = parseConfigResolver.resolve(kb, doc.getParseConfig());
        String parseFingerprint = parseFingerprint(parseOptions);
        recordSourceHash(doc, sourceSha, parseWarnings);
        recordParseFingerprint(doc, parseFingerprint);
        KnowledgeDocument reusable = findReusableDocument(doc, sourceSha, parseFingerprint);
        if (reusable != null && reuseDocument(doc, bytes, reusable, acl, embeddingModel,
                embeddingDimensions, parseWarnings)) {
            return;
        }
        DocumentParser.ParseOutcome outcome = parser.parseWithParentsAndWarnings(
                doc.getFileName(), bytes, parseOptions);
        List<DocumentParser.ParsedChunk> chunks = new ArrayList<>(outcome == null
                ? parser.parseWithParents(doc.getFileName(), bytes, parseOptions) : outcome.chunks());
        parseWarnings.addAll(outcome == null ? List.of() : outcome.warnings());
        String chartEvidence = chunks.stream()
                .map(DocumentParser.ParsedChunk::content)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.joining("\n"));
        if (parseOptions.visionEnabled()) {
            int described = 0;
            int maxImages = Math.max(1, Math.min(parseOptions.visionMaxImages(), 10));
            for (DocumentParser.ExtractedImage image : parser.extractImages(doc.getFileName(), bytes)) {
                if (described >= maxImages) break;
                String description = imageUnderstanding.describe(image.data(), image.extension(), parseOptions);
                if (description == null || description.isBlank()) continue;
                String content = truncateAtSentence("【图片说明】" + description.trim(), 500);
                chunks.add(new DocumentParser.ParsedChunk(content, chunks.size(), content));
                if (description.contains("图") || description.toLowerCase().contains("chart")) {
                    try {
                        com.fasterxml.jackson.databind.JsonNode chart =
                                imageUnderstanding.extractChartData(image.data(), image.extension(), parseOptions);
                        if (chart != null) {
                            ChartDataValidator.CrossCheck cross =
                                    ChartDataValidator.crossCheck(chart, chartEvidence);
                            String evidenceSource = "document_text";
                            if (!"verified".equals(cross.status()) && parseOptions.ocrEnabled()) {
                                String imageOcr = parser.ocrText(image.data(), image.extension(), parseOptions);
                                if (imageOcr != null && !imageOcr.isBlank()) {
                                    cross = ChartDataValidator.crossCheck(chart,
                                            chartEvidence + "\n" + imageOcr);
                                    evidenceSource = "document_text+ocr";
                                }
                            }
                            ObjectNode enriched = ((ObjectNode) chart).deepCopy();
                            enriched.put("validationStatus", cross.status());
                            enriched.put("validationSource", evidenceSource);
                            enriched.put("validationChecked", cross.checked());
                            enriched.put("validationMissing", cross.missing());
                            if ("conflict".equals(cross.status())) enriched.put("confidence", "low");
                            String chartText = buildChartChunk(enriched, parseWarnings);
                            String chartError = ChartDataValidator.validate(chartText);
                            if (chartError == null) {
                                chunks.add(new DocumentParser.ParsedChunk(chartText, chunks.size(), chartText));
                            } else {
                                String message = "图表 JSON 校验失败，已跳过：doc=" + doc.getId() + " " + chartError;
                                log.warn(message);
                                parseWarnings.add("图表结构化内容校验失败，已跳过：" + chartError);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("图表结构化分段生成失败，已跳过 doc={}：{}", doc.getId(), e.getMessage());
                        parseWarnings.add("图表结构化分段生成失败，已跳过：" + e.getMessage());
                    }
                }
                described++;
            }
            if (described > 0) log.info("文档图片多模态分段：doc={} images={}", doc.getId(), described);
        }
        float[][] vectors = embedChunksWithCache(chunks, embeddingModel);
        // 业务有效期优先；未配置时回退文档入库时间，而不是向量写入时间。
        LocalDateTime effectiveFrom = doc.getEffectiveFrom() != null
                ? doc.getEffectiveFrom() : doc.getCreatedAt();
        if (effectiveFrom == null) effectiveFrom = LocalDateTime.now();
        LocalDateTime effectiveTo = doc.getEffectiveTo();

        String profileText = chunks.stream().map(DocumentParser.ParsedChunk::content)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.joining("\n"));
        ChunkMetadataExtractor.Profile profile = ChunkMetadataExtractor.profile(
                doc.getFileName(), profileText.length() > 100_000
                        ? profileText.substring(0, 100_000) : profileText);
        List<KnowledgeChunk> chunkRows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            DocumentParser.ParsedChunk parsed = chunks.get(i);
            ChunkMetadataExtractor.Metadata meta = ChunkMetadataExtractor.extract(doc.getId(), parsed, profile);
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setDocumentId(doc.getId());
            chunk.setKnowledgeBaseId(doc.getKnowledgeBaseId());
            chunk.setChunkIndex(i);
            chunk.setParentIndex(parsed.parentIndex());
            chunk.setParentContent(parsed.parentContent());
            chunk.setDocumentType(meta.documentType());
            chunk.setSectionType(meta.sectionType());
            chunk.setChunkLevel(meta.chunkLevel());
            chunk.setParentKey(meta.parentKey());
            chunk.setDomainKeywords(objectMapper.writeValueAsString(meta.domainKeywords()));
            chunk.setRelevanceScore(meta.relevanceScore());
            chunk.setRegulatoryCategory(meta.regulatoryCategory());
            chunk.setContent(parsed.content());
            chunk.setPageFrom(parsed.pageFrom() > 0 ? parsed.pageFrom() : null);
            chunk.setPageTo(parsed.pageTo() > 0 ? parsed.pageTo() : null);
            chunk.setAllowedUsers(usersJson(acl.allowedUsers()));
            chunk.setTenantVisible(acl.tenantWide() ? 1 : 0);
            chunk.setTenantId(doc.getTenantId());
            chunk.setCreatedAt(LocalDateTime.now());
            chunkRows.add(chunk);
        }
        Map<Integer, Long> chunkIds = persistChunks(doc, chunkRows, parseWarnings);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            DocumentParser.ParsedChunk parsed = chunks.get(i);
            Long chunkId = chunkIds.get(i);
            Map<String, Object> row = new HashMap<>();
            row.put("content", parsed.content());
            row.put("embedding", vectors[i]);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("knowledge_base_id", doc.getKnowledgeBaseId());
            metadata.put("document_id", doc.getId());
            metadata.put("chunk_id", chunkId);
            metadata.put("chunk_index", i);
            KnowledgeChunk stored = chunkRows.get(i);
            metadata.put("parent_index", parsed.parentIndex());
            metadata.put("parent_key", stored.getParentKey());
            metadata.put("chunk_level", stored.getChunkLevel());
            metadata.put("document_type", stored.getDocumentType());
            metadata.put("section_type", stored.getSectionType());
            metadata.put("relevance_score", stored.getRelevanceScore());
            metadata.put("regulatory_category", stored.getRegulatoryCategory());
            try {
                metadata.put("domain_keywords", objectMapper.readValue(
                        stored.getDomainKeywords(), List.class));
            } catch (Exception ignored) {
                metadata.put("domain_keywords", List.of());
            }
            if (parsed.pageFrom() > 0) metadata.put("page_from", parsed.pageFrom());
            if (parsed.pageTo() > 0) metadata.put("page_to", parsed.pageTo());
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
        storeOutboxPayload(doc, rows, embeddingDimensions, embeddingModel);
        vectorRepository.saveBatch(doc.getTenantId(), rows, embeddingDimensions);
        if (mysqlJdbcTemplate != null) {
            long vectorCount = vectorRepository.countByDocument(doc.getTenantId(), doc.getId(),
                    embeddingDimensions);
            if (vectorCount != chunks.size()) {
                throw new IllegalStateException("向量写入数量不匹配：expected=" + chunks.size()
                        + " actual=" + vectorCount);
            }
        }
        storeDocumentImages(doc, bytes);
        recordParserVersion(doc);
        recordEmbeddingInfo(doc, embeddingModel, embeddingDimensions);
        updateStatus(doc, "DONE", chunks.size(), null);
        markOutboxDone(doc);
        storeParseWarning(doc, parseWarnings);
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
        performReparse(doc);
        auditService.record(viewer.userId(), "knowledge.document.reparse", "knowledge_document",
                String.valueOf(documentId), "SUCCESS", Map.of("kbId", kbId));
        return Map.of("documentId", documentId, "status", "PENDING");
    }

    /** 存量升级任务内部调用：不再做用户权限校验，任务创建时已校验。 */
    public boolean reparseForUpgrade(Long kbId, Long documentId) {
        KnowledgeDocument doc = docMapper.selectById(documentId);
        if (doc == null || !Objects.equals(doc.getKnowledgeBaseId(), kbId)
                || (!"DONE".equals(doc.getStatus()) && !"FAILED".equals(doc.getStatus()))) {
            return false;
        }
        performReparse(doc);
        return true;
    }

    /** 供 outbox 恢复使用：清理残留后重新排队解析，不做权限校验。 */
    public boolean retryInterruptedForOutbox(Long documentId) {
        KnowledgeDocument doc = docMapper.selectById(documentId);
        if (doc == null) return false;
        cleanupPartial(doc);
        KnowledgeDocument patch = new KnowledgeDocument();
        patch.setId(documentId);
        patch.setStatus("PENDING");
        patch.setChunkCount(0);
        docMapper.updateById(patch);
        triggerParse(doc.getTenantId(), documentId);
        return true;
    }

    /** outbox 恢复超过重试上限时清理残留并置失败。 */
    public void failInterruptedForOutbox(Long documentId, String error) {
        KnowledgeDocument doc = docMapper.selectById(documentId);
        if (doc == null) return;
        cleanupPartial(doc);
        updateStatus(doc, "FAILED", null, error);
        markOutboxFailed(doc, error);
    }

    private void markOutboxPending(KnowledgeDocument doc) {
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

    private void storeOutboxPayload(KnowledgeDocument doc, List<Map<String, Object>> rows,
                                    int dimensions, String embeddingModel) {
        if (vectorOutboxMapper == null || doc == null || doc.getId() == null) return;
        try {
            String payload = objectMapper.writeValueAsString(rows);
            // 超过 8MB 不写 payload，保留重跑解析的降级路径，避免打爆 MySQL 包大小。
            if (payload.length() > 8_000_000) return;
            vectorOutboxMapper.update(null, new LambdaUpdateWrapper<KnowledgeVectorOutbox>()
                    .eq(KnowledgeVectorOutbox::getDocumentId, doc.getId())
                    .set(KnowledgeVectorOutbox::getPayload, payload)
                    .set(KnowledgeVectorOutbox::getEmbeddingModel, embeddingModel)
                    .set(KnowledgeVectorOutbox::getDimensions, dimensions)
                    .set(KnowledgeVectorOutbox::getStatus, "PENDING")
                    .set(KnowledgeVectorOutbox::getAvailableAt, LocalDateTime.now().plusMinutes(5))
                    .set(KnowledgeVectorOutbox::getUpdatedAt, LocalDateTime.now()));
        } catch (Exception e) {
            log.debug("outbox payload 写入失败（回退重跑解析）doc={}：{}", doc.getId(), e.getMessage());
        }
    }

    /** outbox 直接补写向量成功后的文档收尾。 */
    public boolean completeOutboxVectors(Long documentId, int chunkCount) {
        KnowledgeDocument doc = docMapper.selectById(documentId);
        if (doc == null) return false;
        recordParserVersion(doc);
        updateStatus(doc, "DONE", chunkCount, null);
        markOutboxDone(doc);
        return true;
    }

    private void markOutboxDone(KnowledgeDocument doc) {
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

    private void markOutboxFailed(KnowledgeDocument doc, String error) {
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

    private void performReparse(KnowledgeDocument doc) {
        cleanupPartial(doc);
        deleteDocumentImages(doc);
        KnowledgeDocument patch = new KnowledgeDocument();
        patch.setId(doc.getId());
        patch.setStatus("PENDING");
        patch.setChunkCount(0);
        docMapper.updateById(patch);
        triggerParse(doc.getTenantId(), doc.getId());
    }

    /**
     * 存量文档升级：重新解析本知识库中尚未写入 parse_fingerprint 的 DONE 文档，
     * 生成结构化 Markdown、页码、父块、source_sha256 和新解析指纹。每次限量，异步执行。
     */
    public Map<String, Object> reparseLegacyDocuments(Long kbId, int limit,
                                                       KnowledgePermissionService.Viewer viewer) {
        permissionService.requireEdit(kbId, viewer);
        int max = Math.max(1, Math.min(limit, 50));
        List<KnowledgeDocument> docs = docMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getKnowledgeBaseId, kbId)
                        .eq(KnowledgeDocument::getStatus, "DONE")
                        .isNull(KnowledgeDocument::getParseFingerprint)
                        .orderByAsc(KnowledgeDocument::getCreatedAt)
                        .last("LIMIT " + max));
        int triggered = 0;
        int failed = 0;
        if (docs != null) {
            for (KnowledgeDocument doc : docs) {
                try {
                    reparse(kbId, doc.getId(), viewer);
                    triggered++;
                } catch (Exception e) {
                    failed++;
                    log.warn("存量文档升级提交失败 doc={} err={}", doc.getId(), e.getMessage());
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scanned", docs == null ? 0 : docs.size());
        result.put("triggered", triggered);
        result.put("failed", failed);
        result.put("limit", max);
        log.info("存量文档升级：kb={} scanned={} triggered={} failed={}",
                kbId, result.get("scanned"), triggered, failed);
        return result;
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

    /** 按文档当前有效期/入库时间重建该知识库所有向量有效期（修复历史向量回填值）。 */
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

    /** 为加密 PDF 设置/更新密码后重解析；密码加密保存，不接受非 PDF 或解析中文档。 */
    public Map<String, Object> setPdfPasswordAndReparse(Long kbId, Long documentId, String password,
                                                         KnowledgePermissionService.Viewer viewer) {
        permissionService.requireEdit(kbId, viewer);
        KnowledgeDocument doc = docMapper.selectById(documentId);
        if (doc == null || !Objects.equals(doc.getKnowledgeBaseId(), kbId)) {
            throw new BusinessException(404, "文档不存在");
        }
        String name = doc.getFileName() == null ? "" : doc.getFileName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".pdf")) throw new BusinessException(400, "仅 PDF 文档支持设置 PDF 密码");
        if (!"DONE".equals(doc.getStatus()) && !"FAILED".equals(doc.getStatus())) {
            throw new BusinessException(400, "文档解析中，暂不能修改 PDF 密码");
        }
        if (password != null && password.length() > 256) {
            throw new BusinessException(400, "PDF 密码过长（最多 256 字符）");
        }
        Map<String, Object> config;
        try {
            config = doc.getParseConfig() == null || doc.getParseConfig().isBlank()
                    ? new LinkedHashMap<>()
                    : objectMapper.readValue(doc.getParseConfig(), Map.class);
        } catch (Exception e) {
            throw new BusinessException(400, "文档解析配置格式不正确");
        }
        if (password == null || password.isBlank()) config.remove("pdfPassword");
        else config.put("pdfPassword", password);
        String configJson = config.isEmpty() ? null : toJsonQuiet(config);
        String protectedConfig = protectPdfPassword(configJson);
        docMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getId, documentId)
                .set(KnowledgeDocument::getParseConfig, protectedConfig));
        auditService.record(viewer.userId(), "kb.document.pdf_password", "knowledge_document",
                String.valueOf(documentId), "SUCCESS", Map.of("kbId", kbId, "passwordSet",
                        password != null && !password.isBlank()));
        return reparse(kbId, documentId, viewer);
    }

    /** 补偿清理：重试前删除可能已写入的部分分段与向量，并清掉该文档贡献的自动图谱。 */
    private void cleanupPartial(KnowledgeDocument doc) {
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

    private void deleteParentsQuietly(Long documentId) {
        if (parentMapper == null) return;
        try {
            parentMapper.delete(new LambdaQueryWrapper<KnowledgeParent>()
                    .eq(KnowledgeParent::getDocumentId, documentId));
        } catch (Exception e) {
            log.warn("清理父块失败 doc={}：{}", documentId, e.getMessage());
        }
    }

    /** 检索测试（需求文档 3.6(4)）：query 嵌入 → PgVector topK + 阈值过滤；需可见权限。 */
    public List<Map<String, Object>> search(Long kbId, String query, Integer topK, Double threshold,
                                            String mode, KnowledgePermissionService.Viewer viewer) {
        return search(kbId, query, topK, threshold, mode, null, null, viewer);
    }

    /** 带内容有效期范围的检索：向量与关键字都在 SQL 层过滤；时间筛选下跳过图谱扩展。 */
    public List<Map<String, Object>> search(Long kbId, String query, Integer topK, Double threshold,
                                            String mode, Instant effectiveAfter, Instant effectiveBefore,
                                            KnowledgePermissionService.Viewer viewer) {
        return search(kbId, query, topK, threshold, mode, effectiveAfter, effectiveBefore,
                null, null, null, null, null, viewer);
    }

    /** 携带文档类型/章节/监管类别/块层级/最低相关性的混合检索过滤。 */
    public List<Map<String, Object>> search(Long kbId, String query, Integer topK, Double threshold,
                                            String mode, Instant effectiveAfter, Instant effectiveBefore,
                                            String documentType, String sectionType,
                                            String regulatoryCategory, Integer chunkLevel,
                                            Double minRelevance,
                                            KnowledgePermissionService.Viewer viewer) {
        permissionService.requireView(kbId, viewer);
        if (effectiveAfter != null && effectiveBefore != null && effectiveAfter.isAfter(effectiveBefore)) {
            throw new BusinessException(400, "生效开始时间不能晚于生效结束时间");
        }
        Long tenantId = TenantContext.getTenantId();
        Map<String, Object> retrievalConfig = new LinkedHashMap<>();
        if (mode != null && !mode.isBlank()) retrievalConfig.put("mode", mode);
        if (documentType != null && !documentType.isBlank()) retrievalConfig.put("documentType", documentType);
        if (sectionType != null && !sectionType.isBlank()) retrievalConfig.put("sectionType", sectionType);
        if (regulatoryCategory != null && !regulatoryCategory.isBlank()) {
            retrievalConfig.put("regulatoryCategory", regulatoryCategory);
        }
        if (chunkLevel != null) retrievalConfig.put("chunkLevel", chunkLevel);
        if (minRelevance != null) retrievalConfig.put("minRelevance", minRelevance);
        List<Map<String, Object>> hits = new ArrayList<>(hybridRetrieval.search(tenantId, query, kbId,
                topK == null ? 5 : topK,
                threshold == null ? 0.5 : threshold, retrievalConfig,
                viewer.userId(), permissionService.tenantWide(viewer), effectiveAfter, effectiveBefore).hits());
        // 图谱扩展分段没有完整元数据/有效期约束；有筛选时跳过，避免返回不匹配内容。
        boolean metadataFilter = documentType != null || sectionType != null
                || regulatoryCategory != null || chunkLevel != null || minRelevance != null;
        if (effectiveAfter == null && effectiveBefore == null && !metadataFilter) {
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
            m.put("pageFrom", c.getPageFrom());
            m.put("pageTo", c.getPageTo());
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
            String chartError = ChartDataValidator.validate(content);
            if (chartError != null) {
                throw new BusinessException(400, chartError);
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
            float[] vector = embeddingService.embedOne(MarkdownText.toPlain(updatedContent), model);
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

    /**
     * 图表 JSON 不做字符截断。超长时按“系列/数据点/长字符串”压缩，保证仍是合法 JSON；
     * 极端情况下退化为合法摘要并记录 warning，而不是静默丢弃。
     */
    private String buildChartChunk(ObjectNode chart, List<String> warnings) throws Exception {
        String originalJson = objectMapper.writeValueAsString(chart);
        if (CHART_CHUNK_PREFIX.length() + originalJson.length() <= CHART_CHUNK_MAX) {
            return CHART_CHUNK_PREFIX + originalJson;
        }
        ObjectNode compact = chart.deepCopy();
        int[] pointLimits = {60, 40, 20, 10};
        int[] seriesLimits = {20, 12, 8, 4};
        for (int i = 0; i < pointLimits.length; i++) {
            truncateSeriesPoints(compact, pointLimits[i]);
            truncateTopLevelPoints(compact, pointLimits[i]);
            truncateSeries(compact, seriesLimits[i]);
            trimStrings(compact, 300);
            String json = objectMapper.writeValueAsString(compact);
            if (CHART_CHUNK_PREFIX.length() + json.length() <= CHART_CHUNK_MAX) {
                if (i > 0) {
                    warnings.add("图表数据过长，已压缩为每个系列前 " + pointLimits[i]
                            + " 个数据点/最多 " + seriesLimits[i] + " 个系列；完整数据请查看原图");
                }
                return CHART_CHUNK_PREFIX + json;
            }
        }
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("isChart", compact.path("isChart").asBoolean(true));
        summary.put("title", compact.path("title").asText(""));
        summary.put("compacted", true);
        summary.put("note", "图表数据过长，已压缩为摘要；完整数据请查看原图");
        if (compact.get("data") instanceof ArrayNode data) summary.put("dataPointCount", data.size());
        if (compact.get("points") instanceof ArrayNode points) summary.put("pointCount", points.size());
        JsonNode series = compact.get("series");
        if (series instanceof ArrayNode array) {
            ArrayNode names = summary.putArray("series");
            for (JsonNode item : array) {
                ObjectNode shortSeries = names.addObject();
                shortSeries.put("name", item.path("name").asText(""));
                JsonNode points = item.has("points") ? item.get("points")
                        : item.has("data") ? item.get("data") : item.get("values");
                shortSeries.put("pointCount", points != null && points.isArray() ? points.size() : 0);
            }
        }
        warnings.add("图表数据过长，已压缩为摘要；完整数据请查看原图");
        return CHART_CHUNK_PREFIX + objectMapper.writeValueAsString(summary);
    }

    private void truncateTopLevelPoints(ObjectNode chart, int limit) {
        for (String key : new String[]{"data", "points"}) {
            JsonNode value = chart.get(key);
            if (!(value instanceof ArrayNode points) || points.size() <= limit) continue;
            int original = points.size();
            while (points.size() > limit) points.remove(points.size() - 1);
            chart.put("originalPointCount", original);
            chart.put("truncatedPoints", original - limit);
        }
    }

    private void truncateSeriesPoints(ObjectNode chart, int limit) {
        JsonNode series = chart.get("series");
        if (!(series instanceof ArrayNode array)) return;
        for (JsonNode item : array) {
            if (!(item instanceof ObjectNode object)) continue;
            for (String key : new String[]{"points", "data", "values"}) {
                JsonNode value = object.get(key);
                if (!(value instanceof ArrayNode points) || points.size() <= limit) continue;
                int original = points.size();
                while (points.size() > limit) points.remove(points.size() - 1);
                object.put("originalPointCount", original);
                object.put("truncatedPoints", original - limit);
            }
        }
    }

    private void truncateSeries(ObjectNode chart, int limit) {        JsonNode series = chart.get("series");
        if (!(series instanceof ArrayNode array) || array.size() <= limit) return;
        int original = array.size();
        while (array.size() > limit) array.remove(array.size() - 1);
        chart.put("originalSeriesCount", original);
        chart.put("omittedSeries", original - limit);
    }

    private void trimStrings(JsonNode node, int maxLength) {
        if (node instanceof ObjectNode object) {
            var fields = object.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                JsonNode value = entry.getValue();
                if (value.isTextual() && value.asText().length() > maxLength) {
                    object.put(entry.getKey(), value.asText().substring(0, maxLength) + "…");
                } else {
                    trimStrings(value, maxLength);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (JsonNode item : array) trimStrings(item, maxLength);
        }
    }

    private static boolean isChartChunk(String content) {
        return content != null && content.startsWith(CHART_CHUNK_MARKER);
    }

    public List<KnowledgeDocument> documents(Long kbId, KnowledgePermissionService.Viewer viewer) {
        permissionService.requireView(kbId, viewer);
        List<KnowledgeDocument> docs = docMapper.selectList(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getKnowledgeBaseId, kbId)
                .orderByDesc(KnowledgeDocument::getCreatedAt));
        for (KnowledgeDocument doc : docs) {
            doc.setUpgradePending("DONE".equals(doc.getStatus())
                    && (doc.getParseFingerprint() == null
                    || doc.getParserVersion() == null
                    || !DocumentParser.PARSER_VERSION.equals(doc.getParserVersion())));
        }
        return docs;
    }

    /** 存量文档回填文件 SHA256；每次限量，避免一次读大量 MinIO 对象。 */
    public Map<String, Object> backfillSourceHashes(Long kbId, int limit,
                                                    KnowledgePermissionService.Viewer viewer) {
        permissionService.requireEdit(kbId, viewer);
        int max = Math.max(1, Math.min(limit, 100));
        List<KnowledgeDocument> docs = docMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getKnowledgeBaseId, kbId)
                        .eq(KnowledgeDocument::getStatus, "DONE")
                        .isNull(KnowledgeDocument::getSourceSha256)
                        .last("LIMIT " + max));
        return backfillSourceHashRows(docs);
    }

    /** 定时回填调用：当前租户下所有知识库中缺失 SHA256 的 DONE 文档。 */
    public Map<String, Object> backfillSourceHashesForTenant(int limit) {
        int max = Math.max(1, Math.min(limit, 100));
        List<KnowledgeDocument> docs = docMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getStatus, "DONE")
                        .isNull(KnowledgeDocument::getSourceSha256)
                        .orderByAsc(KnowledgeDocument::getId)
                        .last("LIMIT " + max));
        return backfillSourceHashRows(docs);
    }

    private Map<String, Object> backfillSourceHashRows(List<KnowledgeDocument> docs) {
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
        if ("PARSING".equals(status)) patch.setParseStartedAt(LocalDateTime.now());
        if (doc.getUpgradeJobId() != null) {
            if ("DONE".equals(status)) patch.setUpgradeStatus("SUCCEEDED");
            else if ("FAILED".equals(status)) patch.setUpgradeStatus("FAILED");
        }
        docMapper.updateById(patch);
    }

    private KnowledgeDocument findReusableDocument(KnowledgeDocument doc, String sourceSha,
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

    /** 解析配置指纹：同内容、同配置才允许跨知识库复用。 */
    private String parseFingerprint(ParseOptions options) {
        if (options == null) return null;
        try {
            return sha256(objectMapper.writeValueAsBytes(options));
        } catch (Exception e) {
            log.debug("解析配置指纹生成失败（忽略）：{}", e.getMessage());
            return null;
        }
    }

    private void recordParserVersion(KnowledgeDocument doc) {
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

    private void recordEmbeddingInfo(KnowledgeDocument doc, String model, int dimensions) {
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

    private void recordParseFingerprint(KnowledgeDocument doc, String fingerprint) {
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
    private boolean reuseDocument(KnowledgeDocument doc, byte[] bytes, KnowledgeDocument source,
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
            Map<Integer, Long> chunkIds = persistChunks(doc, newChunks, warnings);
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
                storeDocumentImages(doc, bytes);
            } catch (Exception e) {
                log.warn("复用文档图片存储失败（忽略）doc={} err={}", doc.getId(), e.getMessage());
            }
            String reuseMessage = Objects.equals(source.getKnowledgeBaseId(), doc.getKnowledgeBaseId())
                    ? "已复用相同文件「" + source.getFileName() + "」的解析结果"
                    : "已复用同租户相同文件的解析结果";
            warnings.add(reuseMessage);
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

    /** 文件 SHA256：用于重复上传提示；不阻断解析。 */
    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    private String contentHash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(String.valueOf(text).hashCode());
        }
    }

    private void recordSourceHash(KnowledgeDocument doc, String sha256, List<String> warnings) {
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

    /**
     * 嵌入缓存：按 embedding_model + SHA256(embeddingText) 复用向量。
     * 缓存不可用时自动回退全量嵌入；缓存写入失败不影响本次解析。
     */
    private float[][] embedChunksWithCache(List<DocumentParser.ParsedChunk> chunks, String model) {
        cleanupEmbeddingCacheIfNeeded();
        List<String> texts = chunks.stream()
                .map(DocumentParser.ParsedChunk::embeddingContent).toList();
        if (mysqlJdbcTemplate == null || texts.isEmpty()) {
            return embeddingService.embedBatch(texts, model);
        }
        LinkedHashMap<String, Integer> unique = new LinkedHashMap<>();
        for (int i = 0; i < texts.size(); i++) unique.putIfAbsent(texts.get(i), i);
        Map<String, float[]> cached = loadEmbeddingCache(model, unique.keySet());
        List<String> missing = unique.keySet().stream()
                .filter(text -> !cached.containsKey(text)).toList();
        if (!missing.isEmpty()) {
            float[][] generated = embeddingService.embedBatch(missing, model);
            for (int i = 0; i < missing.size(); i++) {
                cached.put(missing.get(i), generated[i]);
            }
            storeEmbeddingCache(model, missing, generated);
        }
        float[][] vectors = new float[texts.size()][];
        for (int i = 0; i < texts.size(); i++) {
            vectors[i] = cached.get(texts.get(i));
        }
        return vectors;
    }

    private void cleanupEmbeddingCacheIfNeeded() {
        if (mysqlJdbcTemplate == null) return;
        long now = System.currentTimeMillis();
        long last = lastCacheCleanupAt.get();
        if (now - last < 3_600_000L || !lastCacheCleanupAt.compareAndSet(last, now)) return;
        try {
            int days = Math.max(1, embeddingCacheTtlDays);
            mysqlJdbcTemplate.update("DELETE FROM knowledge_embedding_cache WHERE created_at < " +
                    "DATE_SUB(NOW(), INTERVAL " + days + " DAY) LIMIT 10000");
            int maxEntries = Math.max(1000, embeddingCacheMaxEntries);
            mysqlJdbcTemplate.update("DELETE FROM knowledge_embedding_cache WHERE id IN (" +
                    "SELECT id FROM (SELECT id FROM knowledge_embedding_cache " +
                    "ORDER BY created_at DESC LIMIT " + maxEntries + ", 10000) t)");
        } catch (Exception e) {
            log.debug("embedding 缓存清理失败（忽略）：{}", e.getMessage());
        }
    }

    private Map<String, float[]> loadEmbeddingCache(String model, java.util.Collection<String> texts) {
        Map<String, float[]> cached = new HashMap<>();
        if (texts == null || texts.isEmpty()) return cached;
        try {
            List<String> hashes = texts.stream().map(this::contentHash).toList();
            for (int start = 0; start < hashes.size(); start += 500) {
                List<String> batch = hashes.subList(start, Math.min(hashes.size(), start + 500));
                String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
                List<Object> args = new ArrayList<>();
                args.add(model);
                args.addAll(batch);
                List<Map<String, Object>> rows = mysqlJdbcTemplate.queryForList(
                        "SELECT content_hash, embedding FROM knowledge_embedding_cache " +
                                "WHERE embedding_model = ? AND content_hash IN (" + placeholders + ")",
                        args.toArray());
                for (Map<String, Object> row : rows) {
                    Object hash = row.get("content_hash");
                    Object embedding = row.get("embedding");
                    if (hash == null || embedding == null) continue;
                    try {
                        cached.put(String.valueOf(hash),
                                objectMapper.readValue(String.valueOf(embedding), float[].class));
                    } catch (Exception ignored) {
                        // 缓存损坏时按 miss 处理
                    }
                }
            }
        } catch (Exception e) {
            log.warn("embedding 缓存读取失败，回退全量嵌入：{}", e.getMessage());
        }
        return cached;
    }

    private void storeEmbeddingCache(String model, List<String> texts, float[][] vectors) {
        if (texts.isEmpty()) return;
        try {
            String sql = "INSERT INTO knowledge_embedding_cache " +
                    "(embedding_model, content_hash, dimensions, embedding) VALUES (?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE dimensions = VALUES(dimensions), embedding = VALUES(embedding)";
            List<Object[]> batch = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                batch.add(new Object[]{model, contentHash(texts.get(i)), vectors[i].length,
                        objectMapper.writeValueAsString(vectors[i])});
            }
            for (int start = 0; start < batch.size(); start += 500) {
                mysqlJdbcTemplate.batchUpdate(sql,
                        batch.subList(start, Math.min(batch.size(), start + 500)));
            }
        } catch (Exception e) {
            log.warn("embedding 缓存写入失败（不影响解析）：{}", e.getMessage());
        }
    }

    /**
     * 父块/分段写入放在同一 MySQL 事务里；批量失败回退逐条也在事务内。
     * PgVector 不在同一事务，后续失败由 cleanupPartial 删除补偿。
     */
    private Map<Integer, Long> persistChunks(KnowledgeDocument doc, List<KnowledgeChunk> chunks,
                                             List<String> warnings) {
        if (transactionManager == null) {
            return persistChunksInTransaction(doc, chunks, warnings);
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        return template.execute(status -> {
            Map<Integer, Long> ids = persistChunksInTransaction(doc, chunks, warnings);
            if (ids.size() != chunks.size()) {
                throw new IllegalStateException("分段写入数量不匹配：expected=" + chunks.size()
                        + " actual=" + ids.size());
            }
            return ids;
        });
    }

    private Map<Integer, Long> persistChunksInTransaction(KnowledgeDocument doc,
                                                          List<KnowledgeChunk> chunks,
                                                          List<String> warnings) {
        if (storeParents(doc, chunks, warnings)) {
            for (KnowledgeChunk chunk : chunks) chunk.setParentContent(null);
        }
        return insertChunks(doc, chunks);
    }

    /** 父块只存一份；失败时保留 chunk.parent_content 回退，不影响正文。 */
    private boolean storeParents(KnowledgeDocument doc, List<KnowledgeChunk> chunks,
                                 List<String> warnings) {
        if (parentMapper == null) return false;
        Map<Integer, KnowledgeChunk> parents = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : chunks) {
            if (chunk.getParentIndex() == null || chunk.getParentContent() == null
                    || chunk.getParentContent().isBlank()) continue;
            parents.putIfAbsent(chunk.getParentIndex(), chunk);
        }
        if (parents.isEmpty()) return false;
        try {
            if (mysqlJdbcTemplate != null) {
                mysqlJdbcTemplate.update("DELETE FROM knowledge_parent WHERE document_id = ?", doc.getId());
                String sql = "INSERT INTO knowledge_parent " +
                        "(document_id, knowledge_base_id, parent_index, content, document_type, " +
                        "section_type, chunk_level, domain_keywords, relevance_score, " +
                        "regulatory_category, tenant_id, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                List<Object[]> batch = new ArrayList<>(parents.size());
                LocalDateTime now = LocalDateTime.now();
                for (var entry : parents.entrySet()) {
                    KnowledgeChunk chunk = entry.getValue();
                    batch.add(new Object[]{doc.getId(), doc.getKnowledgeBaseId(), entry.getKey(),
                            chunk.getParentContent(), chunk.getDocumentType(), chunk.getSectionType(),
                            chunk.getChunkLevel(), chunk.getDomainKeywords(), chunk.getRelevanceScore(),
                            chunk.getRegulatoryCategory(), doc.getTenantId(),
                            java.sql.Timestamp.valueOf(now)});
                }
                for (int start = 0; start < batch.size(); start += 500) {
                    mysqlJdbcTemplate.batchUpdate(sql,
                            batch.subList(start, Math.min(batch.size(), start + 500)));
                }
                return true;
            }
            parentMapper.delete(new LambdaQueryWrapper<KnowledgeParent>()
                    .eq(KnowledgeParent::getDocumentId, doc.getId()));
            for (var entry : parents.entrySet()) {
                KnowledgeChunk chunk = entry.getValue();
                KnowledgeParent parent = new KnowledgeParent();
                parent.setDocumentId(doc.getId());
                parent.setKnowledgeBaseId(doc.getKnowledgeBaseId());
                parent.setParentIndex(entry.getKey());
                parent.setContent(chunk.getParentContent());
                parent.setDocumentType(chunk.getDocumentType());
                parent.setSectionType(chunk.getSectionType());
                parent.setChunkLevel(chunk.getChunkLevel());
                parent.setDomainKeywords(chunk.getDomainKeywords());
                parent.setRelevanceScore(chunk.getRelevanceScore());
                parent.setRegulatoryCategory(chunk.getRegulatoryCategory());
                parent.setTenantId(doc.getTenantId());
                parent.setCreatedAt(LocalDateTime.now());
                parentMapper.insert(parent);
            }
            return true;
        } catch (Exception e) {
            log.warn("父块独立存储失败，回退 chunk.parent_content doc={} err={}",
                    doc.getId(), e.getMessage());
            warnings.add("父块独立存储失败，已回退重复存储：" + e.getMessage());
            return false;
        }
    }

    /** MySQL 分段批量写入；批量失败时清理并回退逐条插入，保证不产生半批数据。 */
    private Map<Integer, Long> insertChunks(KnowledgeDocument doc, List<KnowledgeChunk> chunks) {
        Map<Integer, Long> ids = new HashMap<>();
        if (mysqlJdbcTemplate != null && chunks.size() > 1) {
            try {
                String sql = """
                        INSERT INTO knowledge_chunk
                        (document_id, knowledge_base_id, chunk_index, parent_index, parent_content,
                         page_from, page_to, content, document_type, section_type, chunk_level,
                         parent_key, domain_keywords, relevance_score, regulatory_category,
                         allowed_users, tenant_visible, tenant_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """;
                List<Object[]> batch = new ArrayList<>(chunks.size());
                for (KnowledgeChunk chunk : chunks) {
                    batch.add(new Object[]{chunk.getDocumentId(), chunk.getKnowledgeBaseId(),
                            chunk.getChunkIndex(), chunk.getParentIndex(), chunk.getParentContent(),
                            chunk.getPageFrom(), chunk.getPageTo(), chunk.getContent(),
                            chunk.getDocumentType(), chunk.getSectionType(), chunk.getChunkLevel(),
                            chunk.getParentKey(), chunk.getDomainKeywords(), chunk.getRelevanceScore(),
                            chunk.getRegulatoryCategory(),
                            chunk.getAllowedUsers(), chunk.getTenantVisible(), chunk.getTenantId(),
                            java.sql.Timestamp.valueOf(chunk.getCreatedAt())});
                }
                for (int start = 0; start < batch.size(); start += 500) {
                    mysqlJdbcTemplate.batchUpdate(sql,
                            batch.subList(start, Math.min(batch.size(), start + 500)));
                }
                for (Map<String, Object> row : mysqlJdbcTemplate.queryForList(
                        "SELECT id, chunk_index FROM knowledge_chunk WHERE document_id = ?",
                        doc.getId())) {
                    Object index = row.get("chunk_index");
                    Object id = row.get("id");
                    if (index instanceof Number i && id instanceof Number value) {
                        ids.put(i.intValue(), value.longValue());
                    }
                }
                if (ids.size() == chunks.size()) return ids;
                log.warn("MySQL 批量插入后分段数不匹配，回退逐条写入 doc={} expected={} actual={}",
                        doc.getId(), chunks.size(), ids.size());
                mysqlJdbcTemplate.update("DELETE FROM knowledge_chunk WHERE document_id = ?", doc.getId());
                ids.clear();
            } catch (Exception e) {
                log.warn("MySQL 批量插入失败，回退逐条写入 doc={} err={}", doc.getId(), e.getMessage());
                try {
                    mysqlJdbcTemplate.update("DELETE FROM knowledge_chunk WHERE document_id = ?", doc.getId());
                } catch (Exception ignored) {
                    // 清理失败时由 doParse 的 cleanupPartial 兜底
                }
                ids.clear();
            }
        }
        for (KnowledgeChunk chunk : chunks) {
            chunkMapper.insert(chunk);
            ids.put(chunk.getChunkIndex(), chunk.getId());
        }
        return ids;
    }

    /** 图片说明按句末截断，避免切在句子中间。 */
    private String truncateAtSentence(String text, int max) {
        if (text == null || text.length() <= max) return text;
        int cut = max;
        for (int i = max - 1; i >= max / 2; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?'
                    || c == '；' || c == ';' || c == '\n') {
                cut = i + 1;
                break;
            }
        }
        return text.substring(0, cut).trim() + "…";
    }

    private void storeParseWarning(KnowledgeDocument doc, List<String> warnings) {        String text = warnings == null ? "" : warnings.stream()
                .filter(message -> message != null && !message.isBlank())
                .distinct()
                .collect(java.util.stream.Collectors.joining("；"));
        if (text.length() > 1000) text = text.substring(0, 1000);
        KnowledgeDocument patch = new KnowledgeDocument();
        patch.setId(doc.getId());
        patch.setParseWarning(text);
        try {
            docMapper.updateById(patch);
        } catch (Exception e) {
            log.warn("解析告警写入失败（不影响文档状态）doc={} err={}", doc.getId(), e.getMessage());
        }
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
