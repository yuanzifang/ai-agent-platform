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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.platform.knowledge.service.KnowledgeSettingsSupport.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final VectorStore vectorRepository;
    private final HybridRetrievalService hybridRetrieval;
    @Autowired(required = false)
    private KnowledgeSearchIndexService searchIndexService;
    @Value("${search.keyword.provider:mysql}")
    private String defaultSearchBackend = "mysql";
    @Value("${vector.store:pgvector}")
    private String defaultVectorBackend = "pgvector";
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
    private final CryptoService cryptoService;

    private final KnowledgeUrlFetcher urlFetcher;
    private final KnowledgeChartChunkBuilder chartChunkBuilder;
    private final KnowledgeDocumentMediaStore mediaStore;
    private final KnowledgeEmbeddingCache embeddingCache;
    private final KnowledgeChunkStore chunkStore;
    private final KnowledgeDocumentMaintenanceService maintenanceService;
    private final KnowledgeSourceHashService sourceHashService;
    private final KnowledgeUploadPolicy uploadPolicy;
    private final KnowledgeChunkAclService chunkAclService;
    private final KnowledgeDocumentSupport documentSupport;

    /** MySQL 主库 JdbcTemplate；测试环境未注入时回退逐条 MyBatis insert。 */
    @Autowired(required = false)
    @Qualifier("jdbcTemplate")
    private JdbcTemplate mysqlJdbcTemplate;

    /** 父块独立表；测试环境未注入时回退 chunk.parent_content。 */
    @Autowired(required = false)
    private KnowledgeParentMapper parentMapper;

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

    /** 知识库直传最大文件大小（默认 20MB，与 DocumentParser 上限一致）。 */
    @Value("${knowledge.upload.max-bytes:20971520}")
    private int uploadMaxBytes = 20 * 1024 * 1024;

    /** 文档解析专用线程池（架构 2.3）；队列满时 CallerRuns 保证不丢任务。 */
    @Resource(name = "documentParseExecutor")
    private ThreadPoolTaskExecutor documentParseExecutor;

    public Map<String, Object> create(String name, String description, String visibility,
                                      String embeddingModel, Boolean graphEnabled, Long userId) {
        return create(name, description, visibility, embeddingModel, graphEnabled,
                null, null, null, null, userId);
    }

    /** 创建知识库，并选择该库的关键字/向量后端和 topK 默认/上限。 */
    public Map<String, Object> create(String name, String description, String visibility,
                                      String embeddingModel, Boolean graphEnabled,
                                      String searchBackend, String vectorBackend,
                                      Integer defaultTopK, Integer maxTopK, Long userId) {
        return create(name, description, visibility, embeddingModel, graphEnabled, searchBackend,
                vectorBackend, defaultTopK, maxTopK, null, null, null, null, null, userId);
    }

    public Map<String, Object> create(String name, String description, String visibility,
                                      String embeddingModel, Boolean graphEnabled,
                                      String searchBackend, String vectorBackend,
                                      Integer defaultTopK, Integer maxTopK,
                                      String defaultRetrievalMode, Double defaultScoreThreshold,
                                      Double defaultKeywordWeight, Integer queryCacheTtlSeconds,
                                      Integer queryCacheMaxEntries, Long userId) {
        int[] topK = normalizeTopK(defaultTopK, maxTopK);
        String retrievalMode = normalizeRetrievalMode(defaultRetrievalMode);
        double scoreThreshold = normalizeScoreThreshold(defaultScoreThreshold, 0.5);
        double keywordWeight = normalizeKeywordWeight(defaultKeywordWeight, 0.35);
        int cacheTtl = normalizeCacheTtl(queryCacheTtlSeconds);
        int cacheMax = normalizeCacheMaxEntries(queryCacheMaxEntries);
        quotaService.checkKnowledgeBaseCreate();
        String search = normalizeBackend(searchBackend, defaultSearchBackend,
                java.util.Set.of("MYSQL", "ES"));
        String vector = normalizeBackend(vectorBackend, defaultVectorBackend,
                java.util.Set.of("PGVECTOR", "QDRANT"));
        validateBackendAvailability(search, vector);
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
        kb.setSearchBackend(search);
        kb.setVectorBackend(vector);
        kb.setDefaultTopK(topK[0]);
        kb.setMaxTopK(topK[1]);
        kb.setDefaultRetrievalMode(retrievalMode);
        kb.setDefaultScoreThreshold(scoreThreshold);
        kb.setDefaultKeywordWeight(keywordWeight);
        kb.setQueryCacheTtlSeconds(cacheTtl);
        kb.setQueryCacheMaxEntries(cacheMax);
        kb.setIndexVersion("v1");
        kb.setCreatedAt(LocalDateTime.now());
        kbMapper.insert(kb);
        vectorRepository.ensureTenantSchema(kb.getTenantId());
        if ("ES".equals(search) && searchIndexService != null) {
            searchIndexService.enqueueKnowledgeBase(kb.getTenantId(), kb.getId());
        }
        return Map.of("id", kb.getId());
    }

    private void validateBackendAvailability(String search, String vector) {
        if ("ES".equals(search) && searchIndexService == null) {
            throw new BusinessException(400, "ES 后端未启用，请先配置 SEARCH_ES_ENABLED=true");
        }
        if ("QDRANT".equals(vector)) {
            if (!(vectorRepository instanceof VectorStoreRouter router) || !router.supports("QDRANT")) {
                throw new BusinessException(400, "Qdrant 后端未启用，请先配置 VECTOR_QDRANT_ENABLED=true");
            }
        }
    }

    /** 编辑名称/描述（需求 3.6.3(6)）；需编辑权限。 */
    public void update(Long id, String name, String description, String embeddingModel,
                       Boolean graphEnabled, Map<String, Object> parseConfig,
                       KnowledgePermissionService.Viewer viewer) {
        update(id, name, description, embeddingModel, graphEnabled, parseConfig,
                null, null, null, null, viewer);
    }

    public void update(Long id, String name, String description, String embeddingModel,
                       Boolean graphEnabled, Map<String, Object> parseConfig,
                       String searchBackend, String vectorBackend,
                       Integer defaultTopK, Integer maxTopK,
                       KnowledgePermissionService.Viewer viewer) {
        update(id, name, description, embeddingModel, graphEnabled, parseConfig, searchBackend,
                vectorBackend, defaultTopK, maxTopK, null, null, null, null, null, viewer);
    }

    public void update(Long id, String name, String description, String embeddingModel,
                       Boolean graphEnabled, Map<String, Object> parseConfig,
                       String searchBackend, String vectorBackend,
                       Integer defaultTopK, Integer maxTopK,
                       String defaultRetrievalMode, Double defaultScoreThreshold,
                       Double defaultKeywordWeight, Integer queryCacheTtlSeconds,
                       Integer queryCacheMaxEntries,
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
        long chunkCount = chunkMapper.selectCount(new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getKnowledgeBaseId, id));
        String oldSearch = kb.getSearchBackend() == null ? "MYSQL" : kb.getSearchBackend();
        String oldVector = kb.getVectorBackend() == null ? "PGVECTOR" : kb.getVectorBackend();
        String newSearch = searchBackend == null || searchBackend.isBlank()
                ? oldSearch : normalizeBackend(searchBackend, oldSearch, java.util.Set.of("MYSQL", "ES"));
        String newVector = vectorBackend == null || vectorBackend.isBlank()
                ? oldVector : normalizeBackend(vectorBackend, oldVector, java.util.Set.of("PGVECTOR", "QDRANT"));
        validateBackendAvailability(newSearch, newVector);
        int[] topK = normalizeTopK(
                defaultTopK == null ? kb.getDefaultTopK() : defaultTopK,
                maxTopK == null ? kb.getMaxTopK() : maxTopK);
        String retrievalMode = defaultRetrievalMode == null || defaultRetrievalMode.isBlank()
                ? normalizeRetrievalMode(kb.getDefaultRetrievalMode()) : normalizeRetrievalMode(defaultRetrievalMode);
        double scoreThreshold = defaultScoreThreshold == null
                ? normalizeScoreThreshold(kb.getDefaultScoreThreshold(), 0.5)
                : normalizeScoreThreshold(defaultScoreThreshold, 0.5);
        double keywordWeight = defaultKeywordWeight == null
                ? normalizeKeywordWeight(kb.getDefaultKeywordWeight(), 0.35)
                : normalizeKeywordWeight(defaultKeywordWeight, 0.35);
        int cacheTtl = queryCacheTtlSeconds == null
                ? normalizeCacheTtl(kb.getQueryCacheTtlSeconds()) : normalizeCacheTtl(queryCacheTtlSeconds);
        int cacheMax = queryCacheMaxEntries == null
                ? normalizeCacheMaxEntries(kb.getQueryCacheMaxEntries())
                : normalizeCacheMaxEntries(queryCacheMaxEntries);
        if (!newVector.equalsIgnoreCase(oldVector) && chunkCount > 0) {
            throw new BusinessException(400, "已有文档时不能切换向量后端；请先清空文档或新建知识库");
        }
        if (name != null && name.isBlank()) name = null;
        if (name == null) name = kb.getName();
        String model = kb.getEmbeddingModel();
        if (embeddingModel != null && !embeddingModel.isBlank()
                && !embeddingModel.trim().equals(model)) {
            if (!embeddingService.supports(embeddingModel.trim())) {
                throw new BusinessException(400, "未配置的 embedding 模型：" + embeddingModel);
            }
            if (chunkCount > 0) {
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
                .set(KnowledgeBase::getSearchBackend, newSearch)
                .set(KnowledgeBase::getVectorBackend, newVector)
                .set(KnowledgeBase::getDefaultTopK, topK[0])
                .set(KnowledgeBase::getMaxTopK, topK[1])
                .set(KnowledgeBase::getDefaultRetrievalMode, retrievalMode)
                .set(KnowledgeBase::getDefaultScoreThreshold, scoreThreshold)
                .set(KnowledgeBase::getDefaultKeywordWeight, keywordWeight)
                .set(KnowledgeBase::getQueryCacheTtlSeconds, cacheTtl)
                .set(KnowledgeBase::getQueryCacheMaxEntries, cacheMax)
                .set(graphEnabled != null, KnowledgeBase::getGraphEnabled, graphEnabled)
                // null = 保留原解析配置；空对象 = 清除覆盖并回退默认
                .set(parseConfig != null, KnowledgeBase::getParseConfig, parseConfigValue));
        if (!newSearch.equalsIgnoreCase(oldSearch) && searchIndexService != null) {
            if ("ES".equals(newSearch)) {
                searchIndexService.enqueueKnowledgeBase(kb.getTenantId(), id);
            } else {
                searchIndexService.enqueueKnowledgeBaseDeletes(kb.getTenantId(), id);
            }
        }
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
                .apply(keyword != null && !keyword.isBlank(),
                        "MATCH(name, description) AGAINST({0} IN NATURAL LANGUAGE MODE)", keyword)
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
        m.put("searchBackend", kb.getSearchBackend() == null ? "MYSQL" : kb.getSearchBackend());
        m.put("vectorBackend", kb.getVectorBackend() == null ? "PGVECTOR" : kb.getVectorBackend());
        m.put("defaultTopK", kb.getDefaultTopK() == null ? 5 : kb.getDefaultTopK());
        m.put("maxTopK", kb.getMaxTopK() == null ? 20 : kb.getMaxTopK());
        m.put("defaultRetrievalMode", kb.getDefaultRetrievalMode() == null
                ? "hybrid" : kb.getDefaultRetrievalMode());
        m.put("defaultScoreThreshold", kb.getDefaultScoreThreshold() == null
                ? 0.5 : kb.getDefaultScoreThreshold());
        m.put("defaultKeywordWeight", kb.getDefaultKeywordWeight() == null
                ? 0.35 : kb.getDefaultKeywordWeight());
        m.put("queryCacheTtlSeconds", kb.getQueryCacheTtlSeconds() == null
                ? 300 : kb.getQueryCacheTtlSeconds());
        m.put("queryCacheMaxEntries", kb.getQueryCacheMaxEntries() == null
                ? 2000 : kb.getQueryCacheMaxEntries());
        m.put("indexVersion", kb.getIndexVersion());
        m.put("graphEnabled", Boolean.TRUE.equals(kb.getGraphEnabled()));
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
        graphService.deleteByKnowledgeBase(id);
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getKnowledgeBaseId, id));
        for (KnowledgeDocument doc : docs) documentSupport.deleteParentsQuietly(doc.getId());
        docMapper.delete(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getKnowledgeBaseId, id));
        permissionService.deleteAll(id);
        kbMapper.deleteById(id);
        // 外部对象/向量不可回滚，必须等 MySQL 事务提交后再删，避免回滚后文档仍在但文件已丢。
        KnowledgeCleanupSupport.afterCommit(() -> {
            for (KnowledgeDocument doc : docs) {
                documentSupport.deleteVectorsQuietly(tenantId, doc.getId());
                KnowledgeCleanupSupport.removeObjectQuietly(storage, doc.getObjectName());
                try {
                    mediaStore.delete(doc);
                } catch (Exception e) {
                    log.warn("删除知识库图片失败 doc={} err={}", doc.getId(), e.getMessage());
                }
            }
            try {
                vectorRepository.deleteByKnowledgeBase(tenantId, id);
            } catch (Exception e) {
                log.warn("删除知识库向量失败 kb={} err={}", id, e.getMessage());
            }
        });
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
        graphService.deleteByDocument(kbId, docId);
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getDocumentId, docId));
        documentSupport.deleteParentsQuietly(docId);
        docMapper.deleteById(docId);
        KnowledgeCleanupSupport.afterCommit(() -> {
            documentSupport.deleteVectorsQuietly(kb.getTenantId(), docId);
            KnowledgeCleanupSupport.removeObjectQuietly(storage, doc.getObjectName());
            try {
                mediaStore.delete(doc);
            } catch (Exception e) {
                log.warn("删除文档图片失败 doc={} err={}", docId, e.getMessage());
            }
        });
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
        try {
            graphService.deleteByDocument(kbId, docId);
        } catch (Exception e) {
            log.warn("重试前清理文档图谱失败（继续重试）doc={} err={}", docId, e.getMessage());
        }
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getDocumentId, docId));
        documentSupport.deleteParentsQuietly(docId);
        documentSupport.deleteVectorsQuietly(kb.getTenantId(), docId);
        mediaStore.delete(doc);
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
        uploadPolicy.validateMetadata(fileName, sizeBytes, uploadMaxBytes);
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
        String safeName = uploadPolicy.safeFileName(fileName);
        String objectName = tenantId + "/knowledge/" + kbId + "/"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "/" + safeName;
        String uploadUrl = storage.presignPut(objectName);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uploadUrl", uploadUrl);
        result.put("objectName", objectName);
        if (documentId != null) result.put("documentId", documentId);
        return result;
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
        String protectedConfig = documentSupport.protectPdfPassword(parseConfigJson);
        Long tenantId = TenantContext.getTenantId();
        String expectedPrefix = tenantId + "/knowledge/" + kbId + "/";
        if (objectName == null || !objectName.startsWith(expectedPrefix)) {
            throw new BusinessException(403, "objectName 与登记路径不一致");
        }
        if (!storage.exists(objectName)) {
            throw new BusinessException(400, "文件尚未上传成功（MinIO 中不存在）");
        }
        long actualSize = storage.statSize(objectName);
        if (actualSize > uploadMaxBytes) {
            storage.remove(objectName);
            throw new BusinessException(413, "文件不能超过 " + (uploadMaxBytes / 1024 / 1024) + "MB");
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
            documentSupport.cleanupPartial(doc);
            mediaStore.delete(doc);
            String oldObject = doc.getObjectName();
            doc.setFileName(fileName);
            doc.setObjectName(objectName);
            doc.setStatus("PENDING");
            doc.setChunkCount(0);
            doc.setParseConfig(protectedConfig);
            if (effectiveFrom != null) doc.setEffectiveFrom(KnowledgeUploadPolicy.toLocalDateTime(effectiveFrom));
            if (effectiveTo != null) doc.setEffectiveTo(KnowledgeUploadPolicy.toLocalDateTime(effectiveTo));
            doc.setSizeBytes(storage.statSize(objectName));
            doc.setRetryTimes(0);
            doc.setErrorMessage(null);
            docMapper.updateById(doc);
            // updateById 跳过 null；覆盖上传未带配置时必须显式清空旧解析配置/错误。
            LambdaUpdateWrapper<KnowledgeDocument> clear = new LambdaUpdateWrapper<KnowledgeDocument>()
                    .eq(KnowledgeDocument::getId, doc.getId())
                    .set(KnowledgeDocument::getErrorMessage, null);
            if (protectedConfig == null) clear.set(KnowledgeDocument::getParseConfig, null);
            docMapper.update(null, clear);
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
        doc.setEffectiveFrom(effectiveFrom == null ? null : KnowledgeUploadPolicy.toLocalDateTime(effectiveFrom));
        doc.setEffectiveTo(effectiveTo == null ? null : KnowledgeUploadPolicy.toLocalDateTime(effectiveTo));
        doc.setSizeBytes(storage.statSize(objectName));
        doc.setRetryTimes(0);
        doc.setTenantId(tenantId);
        doc.setCreatedAt(LocalDateTime.now());
        docMapper.insert(doc);

        triggerParse(tenantId, doc.getId());
        return Map.of("documentId", doc.getId(), "status", doc.getStatus());
    }

    /** 已取得文本/文件的通用导入：写入对象存储后复用现有解析/embedding 流水线。 */
    public Map<String, Object> importContent(Long kbId, String fileName, byte[] content, String contentType,
                                             Instant effectiveFrom, Instant effectiveTo,
                                             KnowledgePermissionService.Viewer viewer, String sourceUrl) {
        permissionService.requireEdit(kbId, viewer);
        if (content == null || content.length == 0) throw new BusinessException(400, "导入内容为空");
        if (content.length > importMaxBytes) {
            throw new BusinessException(413, "单个飞书文件超过 " + (importMaxBytes / 1024 / 1024) + "MB 限制");
        }
        if (effectiveFrom != null && effectiveTo != null && effectiveFrom.isAfter(effectiveTo)) {
            throw new BusinessException(400, "内容生效开始时间不能晚于结束时间");
        }
        quotaService.checkStorage(content.length);
        Long tenantId = TenantContext.getTenantId();
        String safeName = uploadPolicy.safeFileName(fileName == null || fileName.isBlank() ? "feishu.md" : fileName);
        String objectName = tenantId + "/knowledge/" + kbId + "/"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "/" + safeName;
        storage.put(objectName, content, contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType);
        Map<String, Object> result = new LinkedHashMap<>(callback(kbId, objectName, null, null,
                effectiveFrom, effectiveTo, viewer));
        result.put("fileName", safeName);
        if (sourceUrl != null && !sourceUrl.isBlank()) result.put("sourceUrl", sourceUrl);
        auditService.record(viewer.userId(), "kb.document.feishu_import", "knowledge_document",
                String.valueOf(result.get("documentId")), "SUCCESS",
                Map.of("kbId", kbId, "fileName", safeName, "size", content.length));
        return result;
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
        KnowledgeUrlFetcher.Fetched fetched;
        try {
            fetched = urlFetcher.fetch(url, importMaxBytes);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "URL 抓取失败：" + urlFetcher.rootMessage(e));
        }
        if (fetched.bytes().length > importMaxBytes) {
            throw new BusinessException(413, "URL 内容超过 " + (importMaxBytes / 1024 / 1024) + "MB 限制");
        }
        quotaService.checkStorage(fetched.bytes().length);
        Long tenantId = TenantContext.getTenantId();
        String fileName = urlFetcher.fileNameFor(fetched.finalUrl(), fetched.contentType());
        String objectName = tenantId + "/knowledge/" + kbId + "/"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "/" + fileName;
        storage.put(objectName, fetched.bytes(), fetched.contentType());
        Map<String, Object> result = new LinkedHashMap<>(callback(kbId, objectName, null, null,
                effectiveFrom, effectiveTo, viewer));
        result.put("sourceUrl", fetched.finalUrl());
        result.put("fileName", fileName);
        auditService.record(viewer.userId(), "kb.document.url_import", "knowledge_document",
                String.valueOf(result.get("documentId")), "SUCCESS",
                Map.of("kbId", kbId, "url", fetched.finalUrl(), "fileName", fileName));
        return result;
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
                documentSupport.cleanupPartial(doc);
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
        documentSupport.updateStatus(doc, "PARSING", null, null);
        documentSupport.markOutboxPending(doc);
        documentSupport.storeParseWarning(doc, List.of());
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
                documentSupport.cleanupPartial(doc);
                if (root instanceof BusinessException business && !isRetryable(business)) {
                    log.info("文档解析遇到确定性错误，不重试：doc={} code={} err={}",
                            documentId, business.getCode(), business.getMessage());
                    documentSupport.updateStatus(doc, "FAILED", null, business.getMessage());
                    documentSupport.markOutboxFailed(doc, business.getMessage());
                    return;
                }
                if (attempt == attempts - 1) {
                    documentSupport.updateStatus(doc, "FAILED", null, root.getMessage());
                    documentSupport.markOutboxFailed(doc, root.getMessage());
                    return;
                }
                try {
                    Thread.sleep(600L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    documentSupport.updateStatus(doc, "FAILED", null, "解析被中断");
                    documentSupport.markOutboxFailed(doc, "解析被中断");
                    return;
                }
                documentSupport.updateStatus(doc, "PARSING", null, null);
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
        long parseStartedAt = System.currentTimeMillis();
        byte[] bytes = storage.get(doc.getObjectName());
        KnowledgeBase kb = kbMapper.selectById(doc.getKnowledgeBaseId());
        KnowledgePermissionService.Acl acl = kb == null
                ? new KnowledgePermissionService.Acl(List.of(), true) : permissionService.aclOf(kb);
        String embeddingModel = kb == null || kb.getEmbeddingModel() == null
                || kb.getEmbeddingModel().isBlank()
                ? embeddingService.model() : kb.getEmbeddingModel();
        int embeddingDimensions = embeddingService.dimension(embeddingModel);
        List<String> parseWarnings = new ArrayList<>();
        String sourceSha = KnowledgeSourceHashService.sha256(bytes);
        // 四级解析配置：env → 平台 → 租户 → 知识库 → 文档（上传时选择）
        ParseOptions parseOptions = parseConfigResolver.resolve(kb, doc.getParseConfig());
        String parseFingerprint = parseFingerprint(parseOptions);
        documentSupport.recordSourceHash(doc, sourceSha, parseWarnings);
        documentSupport.recordParseFingerprint(doc, parseFingerprint);
        KnowledgeDocument reusable = documentSupport.findReusableDocument(doc, sourceSha, parseFingerprint);
        if (reusable != null && documentSupport.reuseDocument(doc, bytes, reusable, acl, embeddingModel,
                embeddingDimensions, parseWarnings)) {
            return;
        }
        String parseName = uploadPolicy.parserFileName(doc.getFileName(), doc.getObjectName());
        DocumentParser.ParseOutcome outcome = parser.parseWithParentsAndWarnings(
                parseName, bytes, parseOptions);
        List<DocumentParser.ParsedChunk> chunks = new ArrayList<>(outcome == null
                ? parser.parseWithParents(parseName, bytes, parseOptions) : outcome.chunks());
        parseWarnings.addAll(outcome == null ? List.of() : outcome.warnings());
        log.info("文档解析阶段：doc={} phase=parse chunks={} ms={}",
                doc.getId(), chunks.size(), System.currentTimeMillis() - parseStartedAt);
        String chartEvidence = chunks.stream()
                .map(DocumentParser.ParsedChunk::content)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.joining("\n"));
        if (parseOptions.visionEnabled()) {
            int described = 0;
            int maxImages = Math.max(1, Math.min(parseOptions.visionMaxImages(), 10));
            for (DocumentParser.ExtractedImage image : parser.extractImages(parseName, bytes)) {
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
                            ArrayNode missingPoints = objectMapper.createArrayNode();
                            cross.missingPoints().forEach(missingPoints::add);
                            enriched.set("validationMissingPoints", missingPoints);
                            if ("conflict".equals(cross.status())) enriched.put("confidence", "low");
                            String chartText = chartChunkBuilder.build(enriched, parseWarnings);
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
        // 该检查器针对 PDF 文本层/表格结构；HTML 的表格可能是图片或站点模板，
        // 不能据此判定解析缺失，避免“地表5厘米”等文本造成误报。
        if (parseName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            PdfManualReviewInspector.inspect(chunks, parseWarnings);
        }
        long embeddingStartedAt = System.currentTimeMillis();
        float[][] vectors = embeddingCache.embedWithCache(chunks, embeddingModel);
        log.info("文档解析阶段：doc={} phase=embedding chunks={} ms={}",
                doc.getId(), chunks.size(), System.currentTimeMillis() - embeddingStartedAt);
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
        long mysqlStartedAt = System.currentTimeMillis();
        Map<Integer, Long> chunkIds = chunkStore.persist(doc, chunkRows, parseWarnings);
        log.info("文档解析阶段：doc={} phase=mysql chunks={} parents={} ms={}",
                doc.getId(), chunkRows.size(), chunkRows.stream()
                        .map(KnowledgeChunk::getParentIndex).filter(java.util.Objects::nonNull).distinct().count(),
                System.currentTimeMillis() - mysqlStartedAt);

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
        long vectorStartedAt = System.currentTimeMillis();
        vectorRepository.saveBatch(doc.getTenantId(), rows, embeddingDimensions);
        if (mysqlJdbcTemplate != null) {
            long vectorCount = vectorRepository.countByDocument(doc.getTenantId(), doc.getId(),
                    embeddingDimensions);
            if (vectorCount != chunks.size()) {
                throw new IllegalStateException("向量写入数量不匹配：expected=" + chunks.size()
                        + " actual=" + vectorCount);
            }
        }
        log.info("文档解析阶段：doc={} phase=vector rows={} ms={}",
                doc.getId(), rows.size(), System.currentTimeMillis() - vectorStartedAt);
        mediaStore.store(doc, bytes);
        documentSupport.recordParserVersion(doc);
        documentSupport.recordEmbeddingInfo(doc, embeddingModel, embeddingDimensions);
        documentSupport.updateStatus(doc, "DONE", chunks.size(), null);
        documentSupport.markOutboxDone(doc);
        documentSupport.storeParseWarning(doc, parseWarnings);
        documentSupport.recordEmbeddingUsage(doc, chunks.size(), embeddingModel);
        // 解析完成后自动生成知识图谱（异步，失败不影响文档状态；可在系统配置关闭）
        graphService.autoExtractAfterParse(doc.getTenantId(), doc.getKnowledgeBaseId(), doc.getId());
        log.info("文档解析完成：doc={} chunks={} totalMs={}",
                doc.getId(), chunks.size(), System.currentTimeMillis() - parseStartedAt);
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
        documentSupport.cleanupPartial(doc);
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
        documentSupport.cleanupPartial(doc);
        documentSupport.updateStatus(doc, "FAILED", null, error);
        documentSupport.markOutboxFailed(doc, error);
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
        documentSupport.recordParserVersion(doc);
        documentSupport.updateStatus(doc, "DONE", chunkCount, null);
        documentSupport.markOutboxDone(doc);
        return true;
    }

    private void performReparse(KnowledgeDocument doc) {
        documentSupport.cleanupPartial(doc);
        mediaStore.delete(doc);
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

    public Map<String, Object> resolveManualReview(Long kbId, Long documentId, String note,
                                                   KnowledgePermissionService.Viewer viewer) {
        return maintenanceService.resolveManualReview(kbId, documentId, note, viewer);
    }

    public Map<String, Object> updateDocumentValidity(Long kbId, Long documentId,
                                                       Instant effectiveFrom, Instant effectiveTo,
                                                       KnowledgePermissionService.Viewer viewer) {
        return maintenanceService.updateDocumentValidity(kbId, documentId, effectiveFrom, effectiveTo, viewer);
    }

    public Map<String, Object> rebuildValidity(Long kbId, KnowledgePermissionService.Viewer viewer) {
        return maintenanceService.rebuildValidity(kbId, viewer);
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
        String configJson = config.isEmpty() ? null : KnowledgeJsonSupport.write(objectMapper, config);
        String protectedConfig = documentSupport.protectPdfPassword(configJson);
        docMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getId, documentId)
                .set(KnowledgeDocument::getParseConfig, protectedConfig));
        auditService.record(viewer.userId(), "kb.document.pdf_password", "knowledge_document",
                String.valueOf(documentId), "SUCCESS", Map.of("kbId", kbId, "passwordSet",
                        password != null && !password.isBlank()));
        return reparse(kbId, documentId, viewer);
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
        KnowledgeBase kb = permissionService.requireView(kbId, viewer);
        int effectiveTopK = topK == null
                ? (kb.getDefaultTopK() == null ? 5 : kb.getDefaultTopK())
                : Math.max(1, topK);
        int maxTopK = kb.getMaxTopK() == null ? 20 : kb.getMaxTopK();
        effectiveTopK = Math.min(effectiveTopK, Math.max(1, maxTopK));
        if (effectiveAfter != null && effectiveBefore != null && effectiveAfter.isAfter(effectiveBefore)) {
            throw new BusinessException(400, "生效开始时间不能晚于生效结束时间");
        }
        Long tenantId = TenantContext.getTenantId();
        Map<String, Object> retrievalConfig = new LinkedHashMap<>();
        String effectiveMode = mode != null && !mode.isBlank()
                ? mode : (kb.getDefaultRetrievalMode() == null ? "hybrid" : kb.getDefaultRetrievalMode());
        retrievalConfig.put("mode", effectiveMode);
        if (kb.getDefaultKeywordWeight() != null) {
            retrievalConfig.putIfAbsent("keywordWeight", kb.getDefaultKeywordWeight());
        }
        if (documentType != null && !documentType.isBlank()) retrievalConfig.put("documentType", documentType);
        if (sectionType != null && !sectionType.isBlank()) retrievalConfig.put("sectionType", sectionType);
        if (regulatoryCategory != null && !regulatoryCategory.isBlank()) {
            retrievalConfig.put("regulatoryCategory", regulatoryCategory);
        }
        if (chunkLevel != null) retrievalConfig.put("chunkLevel", chunkLevel);
        if (minRelevance != null) retrievalConfig.put("minRelevance", minRelevance);
        double effectiveThreshold = threshold == null
                ? (kb.getDefaultScoreThreshold() == null ? 0.5 : kb.getDefaultScoreThreshold())
                : threshold;
        List<Map<String, Object>> hits = new ArrayList<>(hybridRetrieval.search(tenantId, query, kbId,
                effectiveTopK, effectiveThreshold, retrievalConfig,
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
            m.put("documentType", c.getDocumentType());
            m.put("sectionType", c.getSectionType());
            m.put("chunkLevel", c.getChunkLevel());
            m.put("parentKey", c.getParentKey());
            m.put("domainKeywords", KnowledgeJsonSupport.parse(objectMapper, c.getDomainKeywords()));
            m.put("relevanceScore", c.getRelevanceScore());
            m.put("regulatoryCategory", c.getRegulatoryCategory());
            m.put("allowedUsers", KnowledgeJsonSupport.parse(objectMapper, c.getAllowedUsers()));
            m.put("tenantVisible", c.getTenantVisible() == null || c.getTenantVisible() == 1);
            m.put("reviewStatus", c.getReviewStatus());
            m.put("reviewRequired", chartChunkBuilder.isChartChunk(c.getContent())
                    && !"REVIEWED".equals(c.getReviewStatus()));
            m.put("reviewedAt", c.getReviewedAt());
            m.put("reviewedBy", c.getReviewedBy());
            return m;
        }).toList();
        return Map.of("list", list, "total", result.getTotal(), "page", p, "pageSize", size);
    }

    /** 单 chunk 独立授权；具体双写/补偿逻辑在 KnowledgeChunkAclService。 */
    public Map<String, Object> updateChunkAcl(Long kbId, Long chunkId, List<Long> allowedUsers,
                                               boolean tenantVisible,
                                               KnowledgePermissionService.Viewer viewer) {
        return chunkAclService.update(kbId, chunkId, allowedUsers, tenantVisible, viewer);
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
        if (!chartChunkBuilder.isChartChunk(chunk.getContent())) {
            throw new BusinessException(400, "仅图表数据分段支持人工复核");
        }
        String updatedContent = chunk.getContent();
        boolean contentChanged = content != null && !content.isBlank()
                && !content.equals(chunk.getContent());
        if (contentChanged) {
            if (!chartChunkBuilder.isChartChunk(content)) {
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
            // RESOLVED 后即使历史 parseWarning 仍含“人工复核”，也不能继续标记为待处理。
            doc.setManualReviewRequired("PENDING".equals(doc.getManualReviewStatus())
                    || (doc.getManualReviewStatus() == null && doc.getParseWarning() != null
                    && doc.getParseWarning().contains("人工复核")));
        }
        return docs;
    }

    public Map<String, Object> backfillSourceHashes(Long kbId, int limit,
                                                    KnowledgePermissionService.Viewer viewer) {
        return sourceHashService.backfill(kbId, limit, viewer);
    }

    public Map<String, Object> backfillSourceHashesForTenant(int limit) {
        return sourceHashService.backfillForTenant(limit);
    }

    /** 文档图片列表（预签名 URL；需知识库可见权限）→ 文档支撑服务。 */
    public List<Map<String, Object>> documentImages(Long kbId, Long docId,
                                                     KnowledgePermissionService.Viewer viewer) {
        return documentSupport.documentImages(kbId, docId, viewer);
    }

    /** 解析配置指纹：同内容、同配置才允许跨知识库复用。 */
    private String parseFingerprint(ParseOptions options) {
        if (options == null) return null;
        try {
            return KnowledgeSourceHashService.sha256(objectMapper.writeValueAsBytes(options));
        } catch (Exception e) {
            log.debug("解析配置指纹生成失败（忽略）：{}", e.getMessage());
            return null;
        }
    }

    /** 兼容测试反射调用：实际实现见 KnowledgeDocumentSupport。 */
    @SuppressWarnings("unused")
    private String protectPdfPassword(String json) {
        return documentSupport.protectPdfPassword(json);
    }

    /** 兼容测试反射调用：实际实现见 KnowledgeDocumentSupport。 */
    @SuppressWarnings("unused")
    private void recordEmbeddingUsage(KnowledgeDocument doc, int chunkCount, String embeddingModel) {
        documentSupport.recordEmbeddingUsage(doc, chunkCount, embeddingModel);
    }
}
