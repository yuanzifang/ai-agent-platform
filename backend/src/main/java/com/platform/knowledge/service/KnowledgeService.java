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
import com.platform.common.exception.BusinessException;
import com.platform.common.security.NetworkPolicyService;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.entity.KnowledgeChunk;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.mapper.KnowledgeBaseMapper;
import com.platform.knowledge.mapper.KnowledgeChunkMapper;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import com.platform.system.entity.SysUser;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeDocumentMapper docMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final BillingUsageMapper billingMapper;
    private final PricingService pricingService;
    private final QuotaService quotaService;
    private final StorageService storage;
    private final EmbeddingService embeddingService;
    private final VectorRepository vectorRepository;
    private final HybridRetrievalService hybridRetrieval;
    private final DocumentParser parser;
    private final ObjectMapper objectMapper;
    private final KnowledgePermissionService permissionService;
    private final SysUserMapper userMapper;
    private final AgentKnowledgeBaseMapper agentKbMapper;
    private final AgentRegistryMapper agentRegistryMapper;
    private final AuditService auditService;
    private final NetworkPolicyService networkPolicy;

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
                                      String embeddingModel, Long userId) {
        quotaService.checkKnowledgeBaseCreate();
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(name);
        kb.setDescription(description);
        kb.setTenantId(TenantContext.getTenantId());
        kb.setCreatedBy(userId);
        kb.setVisibility("PRIVATE".equalsIgnoreCase(visibility) ? "PRIVATE" : "TENANT");
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
                       KnowledgePermissionService.Viewer viewer) {
        KnowledgeBase kb = permissionService.requireEdit(id, viewer);
        if (name == null || name.isBlank()) throw new BusinessException(400, "知识库名称不能为空");
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
        kbMapper.update(null, new LambdaUpdateWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, id)
                .set(KnowledgeBase::getName, name.trim())
                .set(KnowledgeBase::getDescription,
                        description == null || description.isBlank() ? null : description.trim())
                .set(KnowledgeBase::getEmbeddingModel, finalModel));
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
        int from = (int) Math.min((p - 1) * size, total);
        int to = (int) Math.min(from + size, total);
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
        m.put("embeddingModel", kb.getEmbeddingModel());
        m.put("indexVersion", kb.getIndexVersion());
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
        }
        try {
            vectorRepository.deleteByKnowledgeBase(tenantId, id);
        } catch (Exception e) {
            log.warn("删除知识库向量失败 kb={} err={}（数据库记录继续清理）", id, e.getMessage());
        }
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
        triggerParse(kb.getTenantId(), docId);
        auditService.record(viewer.userId(), "kb.document.retry", "knowledge_document",
                String.valueOf(docId), "SUCCESS", Map.of("kbId", kbId, "retryTimes", used + 1));
        log.info("文档重试解析：kb={} doc={} retry={}/{}", kbId, docId, used + 1, maxParseRetries);
        return Map.of("documentId", docId, "status", "PENDING", "retryTimes", used + 1);
    }

    /** 申请直传：返回预签名 PUT URL 与登记的 objectName（架构 3.8 路径规范）；需编辑权限。 */
    public Map<String, Object> requestUpload(Long kbId, String fileName, Long sizeBytes,
                                             KnowledgePermissionService.Viewer viewer) {
        permissionService.requireEdit(kbId, viewer);
        if (sizeBytes != null && sizeBytes > 0) quotaService.checkStorage(sizeBytes);
        Long tenantId = TenantContext.getTenantId();
        String objectName = tenantId + "/knowledge/" + kbId + "/"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "/" + fileName;
        String uploadUrl = storage.presignPut(objectName);
        return Map.of("uploadUrl", uploadUrl, "objectName", objectName);
    }

    /** 回调：校验对象存在 + 路径前缀一致（防伪造，需求文档 7.2）→ 登记 → 异步解析；需编辑权限。 */
    public Map<String, Object> callback(Long kbId, String objectName, KnowledgePermissionService.Viewer viewer) {
        permissionService.requireEdit(kbId, viewer);
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
        Map<String, Object> result = new LinkedHashMap<>(callback(kbId, objectName, viewer));
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
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            String location = response.headers().firstValue("location").orElse(null);
            if (location == null || location.isBlank()) {
                throw new BusinessException(502, "URL 返回重定向但缺少 Location");
            }
            String next = URI.create(url).resolve(location).toString();
            return fetch(next, depth + 1);
        }
        if (status < 200 || status >= 300) {
            throw new BusinessException(502, "URL 抓取失败：HTTP " + status);
        }
        long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1);
        if (contentLength > importMaxBytes) {
            throw new BusinessException(413, "URL 内容超过 " + (importMaxBytes / 1024 / 1024) + "MB 限制");
        }
        String contentType = response.headers().firstValue("content-type")
                .orElse("text/html").toLowerCase().split(";")[0].trim();
        if (!(contentType.startsWith("text/") || contentType.equals("application/pdf")
                || contentType.equals("application/json")
                || contentType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))) {
            throw new BusinessException(400, "URL 内容类型不支持：" + contentType);
        }
        return new Fetched(response.body(), contentType, url);
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
        List<DocumentParser.ParsedChunk> chunks = parser.parseWithParents(doc.getFileName(), bytes);
        float[][] vectors = embeddingService.embedBatch(
                chunks.stream().map(DocumentParser.ParsedChunk::content).toList(), embeddingModel);

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
            row.put("metadata", objectMapper.writeValueAsString(Map.of(
                    "knowledge_base_id", doc.getKnowledgeBaseId(),
                    "document_id", doc.getId(),
                    "chunk_id", chunk.getId(),
                    "chunk_index", i,
                    "parent_index", parsed.parentIndex(),
                    "embedding_model", embeddingModel,
                    "allowed_users", acl.allowedUsers().stream().map(String::valueOf).toList(),
                    "tenant_visible", acl.tenantWide(),
                    "file_name", doc.getFileName())));
            rows.add(row);
        }
        vectorRepository.saveBatch(doc.getTenantId(), rows, embeddingDimensions);
        updateStatus(doc, "DONE", chunks.size(), null);
        recordEmbeddingUsage(doc, chunks.size(), embeddingModel);
        log.info("文档解析完成：doc={} chunks={}", doc.getId(), chunks.size());
    }

    /** 补偿清理：重试前删除可能已写入的部分分段与向量。 */
    private void cleanupPartial(KnowledgeDocument doc) {
        try {
            chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunk>()
                    .eq(KnowledgeChunk::getDocumentId, doc.getId()));
        } catch (Exception e) {
            log.warn("清理失败分段失败 doc={}：{}", doc.getId(), e.getMessage());
        }
        deleteVectorsQuietly(doc.getTenantId(), doc.getId());
    }

    /** 检索测试（需求文档 3.6(4)）：query 嵌入 → PgVector topK + 阈值过滤；需可见权限。 */
    public List<Map<String, Object>> search(Long kbId, String query, Integer topK, Double threshold,
                                            String mode, KnowledgePermissionService.Viewer viewer) {
        permissionService.requireView(kbId, viewer);
        Long tenantId = TenantContext.getTenantId();
        Map<String, Object> retrievalConfig = mode == null || mode.isBlank()
                ? Map.of() : Map.of("mode", mode);
        return hybridRetrieval.search(tenantId, query, kbId,
                topK == null ? 5 : topK,
                threshold == null ? 0.5 : threshold, retrievalConfig,
                viewer.userId(), permissionService.tenantWide(viewer)).hits();
    }

    /** 文档分段浏览（架构 4.8 KnowledgeDetail/ChunkPanel 数据源）；需可见权限。 */
    public Map<String, Object> chunks(Long kbId, Long documentId, long page, long pageSize,
                                      KnowledgePermissionService.Viewer viewer) {
        permissionService.requireView(kbId, viewer);
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
            m.put("allowedUsers", parseJson(c.getAllowedUsers()));
            m.put("tenantVisible", c.getTenantVisible() == null || c.getTenantVisible() == 1);
            return m;
        }).toList();
        return Map.of("list", list, "total", result.getTotal(), "page", page, "pageSize", pageSize);
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

    private String usersJson(List<Long> users) {
        // MySQL JSON_CONTAINS 与 PgVector jsonb_array_elements_text 都按字符串匹配 userId
        return users.stream().map(String::valueOf).map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
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
            u.setModelName(embeddingService.model());
            u.setInputTokens((long) chunkCount);
            u.setOutputTokens(0L);
            u.setCost(pricingService.cost(doc.getTenantId(), embeddingService.model(), chunkCount, 0));
            u.setStatDate(LocalDate.now());
            u.setCreatedAt(LocalDateTime.now());
            billingMapper.insert(u);
        } catch (Exception e) {
            log.warn("嵌入用量记录失败：{}", e.getMessage());
        }
    }
}
