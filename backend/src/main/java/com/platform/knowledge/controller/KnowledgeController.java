package com.platform.knowledge.controller;

import com.platform.common.context.TenantContext;
import com.platform.common.response.ApiResponse;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.service.EmbeddingService;
import com.platform.knowledge.service.FeishuDocumentService;
import com.platform.knowledge.service.FeishuImportJobService;
import com.platform.knowledge.service.KnowledgePermissionService;
import com.platform.knowledge.service.KnowledgeGraphService;
import com.platform.knowledge.service.KnowledgeSearchIndexService;
import com.platform.knowledge.service.KnowledgeService;
import com.platform.knowledge.service.KnowledgeUpgradeService;
import com.platform.knowledge.service.VectorStore;
import com.platform.knowledge.service.VectorStoreRouter;
import com.platform.security.JwtPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识库接口（需求文档 7.2 / 7.3 字段定义）。
 * 可见/编辑权限在服务层按 knowledge_base.visibility + knowledge_permission 实时判定。
 */
@RestController
@RequestMapping("/api/v1/knowledge-base")
@RequiredArgsConstructor

@io.swagger.v3.oas.annotations.tags.Tag(name = "知识库")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final KnowledgeGraphService graphService;
    private final KnowledgePermissionService permissionService;
    private final EmbeddingService embeddingService;
    private final KnowledgeUpgradeService upgradeService;
    private final FeishuDocumentService feishuDocumentService;
    private final FeishuImportJobService feishuImportJobService;
    private final VectorStore vectorRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private KnowledgeSearchIndexService searchIndexService;

    private static KnowledgePermissionService.Viewer viewer(JwtPrincipal principal) {
        return KnowledgePermissionService.Viewer.of(principal.userId(), principal.roles());
    }

    public record CreateKbRequest(@NotBlank String name, String description, String visibility,
                                  String embeddingModel, Boolean graphEnabled,
                                  String searchBackend, String vectorBackend,
                                  Integer defaultTopK, Integer maxTopK,
                                  String defaultRetrievalMode, Double defaultScoreThreshold,
                                  Double defaultKeywordWeight, Integer queryCacheTtlSeconds,
                                  Integer queryCacheMaxEntries) {
    }

    @PostMapping
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('kb:create')")
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody CreateKbRequest req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.create(req.name(), req.description(), req.visibility(),
                req.embeddingModel(), req.graphEnabled(), req.searchBackend(), req.vectorBackend(),
                req.defaultTopK(), req.maxTopK(), req.defaultRetrievalMode(),
                req.defaultScoreThreshold(), req.defaultKeywordWeight(),
                req.queryCacheTtlSeconds(), req.queryCacheMaxEntries(), principal.userId()));
    }

    /** 列表：可见性过滤 + 搜索 + 分页（仅返回当前用户可见的知识库）。 */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String visibility,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long pageSize,
                                                 @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.list(keyword, visibility, page, pageSize, viewer(principal)));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.detail(id, viewer(principal)));
    }

    public record UpdateKbRequest(String name, String description, String embeddingModel,
                                  Boolean graphEnabled, Map<String, Object> parseConfig,
                                  String searchBackend, String vectorBackend,
                                  Integer defaultTopK, Integer maxTopK,
                                  String defaultRetrievalMode, Double defaultScoreThreshold,
                                  Double defaultKeywordWeight, Integer queryCacheTtlSeconds,
                                  Integer queryCacheMaxEntries) {
    }

    /** 编辑名称/描述（需编辑权限）。 */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody UpdateKbRequest req,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        knowledgeService.update(id, req.name(), req.description(), req.embeddingModel(),
                req.graphEnabled(), req.parseConfig(), req.searchBackend(), req.vectorBackend(),
                req.defaultTopK(), req.maxTopK(), req.defaultRetrievalMode(),
                req.defaultScoreThreshold(), req.defaultKeywordWeight(),
                req.queryCacheTtlSeconds(), req.queryCacheMaxEntries(), viewer(principal));
        return ApiResponse.ok();
    }

    /** 可选的 embedding 模型（名称 + 维度），供知识库创建/编辑下拉。 */
    @GetMapping("/embedding-models")
    public ApiResponse<List<Map<String, Object>>> embeddingModels() {
        return ApiResponse.ok(embeddingService.availableModels());
    }

    /** 知识库可选的检索后端与当前是否已配置。 */
    @GetMapping("/retrieval-backends")
    public ApiResponse<Map<String, Object>> retrievalBackends() {
        boolean es = searchIndexService != null;
        boolean qdrant = vectorRepository instanceof VectorStoreRouter router
                && router.supports("QDRANT");
        return ApiResponse.ok(Map.of(
                "search", List.of(
                        Map.of("value", "MYSQL", "label", "MySQL", "available", true),
                        Map.of("value", "ES", "label", "Elasticsearch", "available", es)),
                "vector", List.of(
                        Map.of("value", "PGVECTOR", "label", "pgvector", "available", true),
                        Map.of("value", "QDRANT", "label", "Qdrant", "available", qdrant))));
    }

    /** 当前知识库的全局/partial HNSW 状态（可见用户可查看）。 */
    @GetMapping("/{id}/vector-indexes")
    public ApiResponse<Map<String, Object>> vectorIndexes(@PathVariable Long id,
                                                          @AuthenticationPrincipal JwtPrincipal principal) {
        KnowledgeBase kb = permissionService.requireView(id, viewer(principal));
        int dimensions = embeddingService.dimension(kb.getEmbeddingModel());
        return ApiResponse.ok(vectorRepository.partialIndexStatus(kb.getTenantId(), id, dimensions));
    }

    /** 手动把当前知识库分段排入 ES 索引 outbox；ES 未开启时返回未配置。 */
    @PostMapping("/{id}/search-index/reindex")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('kb:edit')")
    public ApiResponse<Map<String, Object>> reindexSearch(@PathVariable Long id,
                                                          @AuthenticationPrincipal JwtPrincipal principal) {
        KnowledgeBase kb = permissionService.requireEdit(id, viewer(principal));
        if (searchIndexService == null) {
            return ApiResponse.ok(Map.of("enabled", false, "queued", 0));
        }
        int queued = searchIndexService.enqueueKnowledgeBase(kb.getTenantId(), id);
        return ApiResponse.ok(Map.of("enabled", true, "queued", queued));
    }

    /** 立即执行一次 partial HNSW 维护，不必等待定时任务；需要编辑权限。 */
    @PostMapping("/{id}/vector-indexes/reconcile")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('kb:edit')")
    public ApiResponse<Map<String, Integer>> reconcileVectorIndexes(
            @PathVariable Long id, @AuthenticationPrincipal JwtPrincipal principal) {
        KnowledgeBase kb = permissionService.requireEdit(id, viewer(principal));
        return ApiResponse.ok(vectorRepository.reconcilePartialIndexes(kb.getTenantId()));
    }

    /** 删除前查询引用该知识库的 Agent（需求 3.6.3(6)：被引用时需提示）。 */
    @GetMapping("/{id}/references")
    public ApiResponse<Map<String, Object>> references(@PathVariable Long id,
                                                       @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.references(id, viewer(principal)));
    }

    /** 删除知识库；被 Agent 引用时需 force=true（前端二次确认后携带）。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @RequestParam(defaultValue = "false") boolean force,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        knowledgeService.delete(id, force, viewer(principal));
        return ApiResponse.ok();
    }

    public record PermissionRequest(@NotBlank String visibility,
                                    List<KnowledgePermissionService.Member> members) {
    }

    /** 权限面板数据（需编辑权限）：可见范围 + 已授权成员。 */
    @GetMapping("/{id}/permissions")
    public ApiResponse<Map<String, Object>> permissions(@PathVariable Long id,
                                                        @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(permissionService.permissionOf(id, viewer(principal)));
    }

    /** 权限全量覆盖（PUT 语义，需求 3.6.3(5)）。 */
    @PutMapping("/{id}/permissions")
    public ApiResponse<Void> updatePermissions(@PathVariable Long id,
                                               @Valid @RequestBody PermissionRequest req,
                                               @AuthenticationPrincipal JwtPrincipal principal) {
        permissionService.replace(id, req.visibility(), req.members(), viewer(principal));
        return ApiResponse.ok();
    }

    public record UploadRequest(@NotBlank String fileName, @NotNull Long sizeBytes,
                                @NotBlank String contentType, Long documentId) {
    }

    @PostMapping("/{id}/documents")
    public ApiResponse<Map<String, Object>> requestUpload(@PathVariable Long id,
                                                          @Valid @RequestBody UploadRequest req,
                                                          @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.requestUpload(id, req.fileName(), req.sizeBytes(),
                req.documentId(), viewer(principal)));
    }

    public record CallbackRequest(@NotBlank String objectName, String parseConfig, Long documentId,
                                  Instant effectiveFrom, Instant effectiveTo) {
    }

    @PostMapping("/{id}/documents/callback")
    public ApiResponse<Map<String, Object>> callback(@PathVariable Long id,
                                                     @Valid @RequestBody CallbackRequest req,
                                                     @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.callback(id, req.objectName(), req.documentId(),
                req.parseConfig(), req.effectiveFrom(), req.effectiveTo(), viewer(principal)));
    }

    /** 批量重试本知识库失败文档（受单文档重试上限约束）。 */
    @PostMapping("/{id}/documents/retry-failed")
    public ApiResponse<Map<String, Object>> retryFailed(@PathVariable Long id,
                                                        @RequestParam(defaultValue = "20") int limit,
                                                        @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.retryFailedDocuments(id, limit, viewer(principal)));
    }

    /** 手动回收本知识库超时 PARSING 的文档（默认 6 小时）。 */
    @PostMapping("/{id}/documents/recover-stale")
    public ApiResponse<Map<String, Object>> recoverStale(@PathVariable Long id,
                                                         @RequestParam(defaultValue = "360") int staleMinutes,
                                                         @AuthenticationPrincipal JwtPrincipal principal) {
        int recovered = knowledgeService.recoverStaleParsingForKb(id, staleMinutes, 50, viewer(principal));
        return ApiResponse.ok(Map.of("recovered", recovered));
    }

    /** 重新解析（DONE/FAILED）：清理旧分段/向量/图片后按最新解析配置重跑。 */
    @PostMapping("/{id}/documents/{docId}/reparse")
    public ApiResponse<Map<String, Object>> reparse(@PathVariable Long id, @PathVariable Long docId,
                                                    @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.reparse(id, docId, viewer(principal)));
    }

    public record PdfPasswordRequest(String password) {
    }

    @PostMapping("/{id}/documents/{docId}/pdf-password")
    public ApiResponse<Map<String, Object>> setPdfPassword(@PathVariable Long id, @PathVariable Long docId,
                                                            @RequestBody PdfPasswordRequest req,
                                                            @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.setPdfPasswordAndReparse(id, docId, req.password(),
                viewer(principal)));
    }

    public record DocumentValidityRequest(Instant effectiveFrom, Instant effectiveTo) {
    }

    @PutMapping("/{id}/documents/{docId}/validity")
    public ApiResponse<Map<String, Object>> updateDocumentValidity(
            @PathVariable Long id, @PathVariable Long docId,
            @RequestBody DocumentValidityRequest req,
            @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.updateDocumentValidity(id, docId,
                req.effectiveFrom(), req.effectiveTo(), viewer(principal)));
    }

    @PostMapping("/{id}/validity/rebuild")
    public ApiResponse<Map<String, Object>> rebuildValidity(@PathVariable Long id,
                                                             @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.rebuildValidity(id, viewer(principal)));
    }

    public record UrlImportRequest(@NotBlank String url, Instant effectiveFrom, Instant effectiveTo) {
    }

    /** URL 导入（网页/PDF/文本），复用对象存储 + 异步解析。 */
    @PostMapping("/{id}/documents/url")
    public ApiResponse<Map<String, Object>> importUrl(@PathVariable Long id,
                                                      @Valid @RequestBody UrlImportRequest req,
                                                      @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.importUrl(id, req.url(),
                req.effectiveFrom(), req.effectiveTo(), viewer(principal)));
    }

    public record FeishuImportRequest(@NotBlank String url, @NotBlank String appId,
                                      @NotBlank String appSecret, Instant effectiveFrom,
                                      Instant effectiveTo) {
    }

    /** 飞书 docx/wiki/drive 文件夹导入；凭据只用于本次请求，不落库。 */
    @PostMapping("/{id}/documents/feishu")
    public ApiResponse<Map<String, Object>> importFeishu(@PathVariable Long id,
                                                         @Valid @RequestBody FeishuImportRequest req,
                                                         @AuthenticationPrincipal JwtPrincipal principal) {
        FeishuDocumentService.Collection collection =
                feishuDocumentService.collectDetailed(req.url(), req.appId(), req.appSecret());
        List<Map<String, Object>> imported = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (FeishuDocumentService.Document document : collection.documents()) {
            try {
                imported.add(knowledgeService.importContent(id, document.name(), document.content(),
                        document.contentType(), req.effectiveFrom(), req.effectiveTo(),
                        viewer(principal), document.sourceUrl()));
            } catch (Exception e) {
                failed.add(Map.of("name", document.name(), "error",
                        e.getMessage() == null ? "导入失败" : e.getMessage()));
            }
        }
        return ApiResponse.ok(Map.of("total", collection.documents().size(), "imported", imported.size(),
                "failed", failed.size(), "skipped", collection.skipped().size(),
                "skippedItems", collection.skipped(), "documents", imported, "failures", failed));
    }

    /** 大文件夹异步导入：立即返回 jobId，前端轮询进度。 */
    @PostMapping("/{id}/documents/feishu/jobs")
    public ApiResponse<Map<String, Object>> startFeishuJob(@PathVariable Long id,
                                                           @Valid @RequestBody FeishuImportRequest req,
                                                           @AuthenticationPrincipal JwtPrincipal principal) {
        KnowledgePermissionService.Viewer current = viewer(principal);
        permissionService.requireEdit(id, current);
        String jobId = feishuImportJobService.start(id, req.url(), req.appId(), req.appSecret(),
                req.effectiveFrom(), req.effectiveTo(), current, TenantContext.getTenantId());
        return ApiResponse.ok(Map.of("jobId", jobId));
    }

    @GetMapping("/{id}/documents/feishu/jobs/{jobId}")
    public ApiResponse<Map<String, Object>> feishuJob(@PathVariable Long id, @PathVariable String jobId,
                                                      @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(feishuImportJobService.status(id, jobId, viewer(principal)));
    }

    @GetMapping("/{id}/documents")
    public ApiResponse<List<KnowledgeDocument>> documents(@PathVariable Long id,
                                                          @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.documents(id, viewer(principal)));
    }

    /** 人工确认 PDF 解析告警已核对。 */
    @PostMapping("/{id}/documents/{docId}/manual-review")
    public ApiResponse<Map<String, Object>> resolveManualReview(
            @PathVariable Long id,
            @PathVariable Long docId,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal JwtPrincipal principal) {
        String note = body == null ? null : body.get("note");
        return ApiResponse.ok(knowledgeService.resolveManualReview(id, docId, note, viewer(principal)));
    }

    /** 存量文档回填 source_sha256（每次最多 100 份；需编辑权限）。 */
    @PostMapping("/{id}/documents/backfill-hashes")
    public ApiResponse<Map<String, Object>> backfillHashes(
            @PathVariable Long id,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.backfillSourceHashes(id, limit, viewer(principal)));
    }

    /** 创建存量升级任务（后台分批消费，持久化进度）。 */
    @PostMapping("/{id}/documents/upgrade-jobs")
    public ApiResponse<Map<String, Object>> startUpgradeJob(
            @PathVariable Long id,
            @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(upgradeService.start(id, viewer(principal)));
    }

    /** 最近一次存量升级任务进度。 */
    @GetMapping("/{id}/documents/upgrade-jobs/latest")
    public ApiResponse<Map<String, Object>> latestUpgradeJob(
            @PathVariable Long id,
            @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(upgradeService.latest(id, viewer(principal)));
    }

    /** 暂停存量升级任务。 */
    @PostMapping("/{id}/documents/upgrade-jobs/{jobId}/pause")
    public ApiResponse<Map<String, Object>> pauseUpgradeJob(
            @PathVariable Long id,
            @PathVariable Long jobId,
            @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(upgradeService.pause(id, jobId, viewer(principal)));
    }

    /** 继续已暂停的存量升级任务。 */
    @PostMapping("/{id}/documents/upgrade-jobs/{jobId}/resume")
    public ApiResponse<Map<String, Object>> resumeUpgradeJob(
            @PathVariable Long id,
            @PathVariable Long jobId,
            @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(upgradeService.resume(id, jobId, viewer(principal)));
    }

    /** 取消运行中的存量升级任务。 */
    @PostMapping("/{id}/documents/upgrade-jobs/{jobId}/cancel")
    public ApiResponse<Map<String, Object>> cancelUpgradeJob(
            @PathVariable Long id,
            @PathVariable Long jobId,
            @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(upgradeService.cancel(id, jobId, viewer(principal)));
    }

    /** 指定存量升级任务进度。 */
    @GetMapping("/{id}/documents/upgrade-jobs/{jobId}")
    public ApiResponse<Map<String, Object>> upgradeJob(
            @PathVariable Long id,
            @PathVariable Long jobId,
            @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(upgradeService.status(id, jobId, viewer(principal)));
    }

    /** 存量文档升级：重新解析本库尚未生成 Markdown/页码/指纹的 DONE 文档（每次最多 50 篇）。 */
    @PostMapping("/{id}/documents/reparse-legacy")
    public ApiResponse<Map<String, Object>> reparseLegacyDocuments(
            @PathVariable Long id,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.reparseLegacyDocuments(id, limit, viewer(principal)));
    }

    /** 删除文档（级联 MinIO/PgVector/分段，需编辑权限）。 */
    @DeleteMapping("/{id}/documents/{docId}")
    public ApiResponse<Void> deleteDocument(@PathVariable Long id, @PathVariable Long docId,
                                            @AuthenticationPrincipal JwtPrincipal principal) {
        knowledgeService.deleteDocument(id, docId, viewer(principal));
        return ApiResponse.ok();
    }

    /** 解析失败重试（默认上限 3 次，需编辑权限）。 */
    @PostMapping("/{id}/documents/{docId}/retry")
    public ApiResponse<Map<String, Object>> retryDocument(@PathVariable Long id, @PathVariable Long docId,
                                                          @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.retryDocument(id, docId, viewer(principal)));
    }

    @GetMapping("/{id}/documents/{docId}/images")
    public ApiResponse<List<Map<String, Object>>> documentImages(@PathVariable Long id,
                                                                  @PathVariable Long docId,
                                                                  @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.documentImages(id, docId, viewer(principal)));
    }

    @GetMapping("/{id}/documents/{docId}/chunks")
    public ApiResponse<Map<String, Object>> chunks(@PathVariable Long id, @PathVariable Long docId,
                                                   @RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "10") long pageSize,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.chunks(id, docId, page, pageSize, viewer(principal)));
    }

    public record ChunkAclRequest(List<Long> allowedUsers, Boolean tenantVisible) {
    }

    @PutMapping("/{id}/chunks/{chunkId}/acl")
    public ApiResponse<Map<String, Object>> updateChunkAcl(@PathVariable Long id, @PathVariable Long chunkId,
                                                            @RequestBody ChunkAclRequest req,
                                                            @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.updateChunkAcl(id, chunkId, req.allowedUsers(),
                req.tenantVisible() == null || req.tenantVisible(), viewer(principal)));
    }

    public record ChunkReviewRequest(Boolean reviewed, String content) {
    }

    @PutMapping("/{id}/chunks/{chunkId}/review")
    public ApiResponse<Map<String, Object>> reviewChunk(@PathVariable Long id, @PathVariable Long chunkId,
                                                         @RequestBody ChunkReviewRequest req,
                                                         @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.updateChunkReview(id, chunkId,
                Boolean.TRUE.equals(req.reviewed()), req.content(), viewer(principal)));
    }

    public record SearchRequest(@NotBlank String query, Integer topK, Double scoreThreshold, String mode,
                                Instant effectiveAfter, Instant effectiveBefore,
                                String documentType, String sectionType, String regulatoryCategory,
                                Integer chunkLevel, Double minRelevance) {
    }

    @GetMapping("/{id}/graph")
    public ApiResponse<Map<String, Object>> graph(@PathVariable Long id,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(graphService.graph(id, viewer(principal)));
    }

    /** 解析后自动抽取之外，允许对全库 DONE 文档做一次异步重建（保留手工实体/关系）。 */
    @PostMapping("/{id}/graph/build")
    public ApiResponse<Map<String, Object>> buildGraph(@PathVariable Long id,
                                                        @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(graphService.buildAll(id, viewer(principal)));
    }

    @GetMapping("/{id}/graph/jobs/latest")
    public ApiResponse<Map<String, Object>> latestGraphJob(@PathVariable Long id,
                                                           @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(graphService.latestJob(id, viewer(principal)));
    }

    @GetMapping("/{id}/graph/communities")
    public ApiResponse<Map<String, Object>> graphCommunities(@PathVariable Long id,
                                                             @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(graphService.communities(id, viewer(principal)));
    }

    @PostMapping("/{id}/graph/entities/embed")
    public ApiResponse<Map<String, Object>> embedGraphEntities(@PathVariable Long id,
                                                               @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(graphService.embedEntities(id, viewer(principal)));
    }

    @PostMapping("/{id}/graph/entities/search")
    public ApiResponse<Map<String, Object>> searchGraphEntities(@PathVariable Long id,
                                                                @RequestBody Map<String, Object> req,
                                                                @AuthenticationPrincipal JwtPrincipal principal) {
        String query = req.get("query") == null ? "" : String.valueOf(req.get("query"));
        int topK = req.get("topK") instanceof Number n ? n.intValue() : 5;
        return ApiResponse.ok(graphService.searchEntities(id, query, topK, viewer(principal)));
    }

    @PostMapping("/{id}/graph/communities/search")
    public ApiResponse<Map<String, Object>> searchCommunities(@PathVariable Long id,
                                                             @RequestBody Map<String, Object> req,
                                                             @AuthenticationPrincipal JwtPrincipal principal) {
        String query = req.get("query") == null ? "" : String.valueOf(req.get("query"));
        int topK = req.get("topK") instanceof Number n ? n.intValue() : 5;
        return ApiResponse.ok(graphService.searchCommunities(id, query, topK, viewer(principal)));
    }

    @GetMapping("/{id}/graph/communities/saved")
    public ApiResponse<Map<String, Object>> savedCommunities(@PathVariable Long id,
                                                             @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(graphService.savedCommunities(id, viewer(principal)));
    }

    @PostMapping("/{id}/graph/communities/rebuild")
    public ApiResponse<Map<String, Object>> rebuildCommunities(@PathVariable Long id,
                                                               @RequestBody(required = false) Map<String, Object> req,
                                                               @AuthenticationPrincipal JwtPrincipal principal) {
        String model = req == null ? null : String.valueOf(req.getOrDefault("model", ""));
        int limit = req != null && req.get("limit") instanceof Number n ? n.intValue() : 5;
        return ApiResponse.ok(graphService.rebuildCommunities(id, model, limit, viewer(principal)));
    }

    @PostMapping("/{id}/graph/communities/summarize")
    public ApiResponse<Map<String, Object>> summarizeCommunities(@PathVariable Long id,
                                                                @RequestBody(required = false) Map<String, Object> req,
                                                                @AuthenticationPrincipal JwtPrincipal principal) {
        String model = req == null ? null : String.valueOf(req.getOrDefault("model", ""));
        int limit = req != null && req.get("limit") instanceof Number n ? n.intValue() : 5;
        return ApiResponse.ok(graphService.summarizeCommunities(id, model, limit, viewer(principal)));
    }

    @GetMapping("/{id}/graph/entities/{entityId}")
    public ApiResponse<Map<String, Object>> graphEntity(@PathVariable Long id, @PathVariable Long entityId,
                                                        @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(graphService.entityDetail(id, entityId, viewer(principal)));
    }

    @PostMapping("/{id}/graph/extract")
    public ApiResponse<Map<String, Object>> extractGraph(@PathVariable Long id,
                                                          @RequestBody Map<String, Object> req,
                                                          @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(graphService.extract(id, req, viewer(principal)));
    }

    /** 提交 AI 抽取预览结果，避免用户确认时重复调用模型。 */
    @PostMapping("/{id}/graph/commit")
    public ApiResponse<Map<String, Object>> commitGraph(@PathVariable Long id,
                                                         @RequestBody Map<String, Object> req,
                                                         @AuthenticationPrincipal JwtPrincipal principal) {
        var commitResult = graphService.commitPreview(id, req, viewer(principal));
        graphService.autoRefreshCommunities(null, id);
        return ApiResponse.ok(commitResult);
    }

    @PostMapping("/{id}/graph/entities")
    public ApiResponse<com.platform.knowledge.entity.KnowledgeGraphEntity> createEntity(
            @PathVariable Long id, @RequestBody Map<String, Object> req,
            @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(graphService.createEntity(id, req, viewer(principal)));
    }

    @PutMapping("/{id}/graph/entities/{entityId}")
    public ApiResponse<com.platform.knowledge.entity.KnowledgeGraphEntity> updateEntity(
            @PathVariable Long id, @PathVariable Long entityId, @RequestBody Map<String, Object> req,
            @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(graphService.updateEntity(id, entityId, req, viewer(principal)));
    }

    @DeleteMapping("/{id}/graph/entities/{entityId}")
    public ApiResponse<Void> deleteEntity(@PathVariable Long id, @PathVariable Long entityId,
                                          @AuthenticationPrincipal JwtPrincipal principal) {
        graphService.deleteEntity(id, entityId, viewer(principal));
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/graph/relations")
    public ApiResponse<com.platform.knowledge.entity.KnowledgeGraphRelation> createRelation(
            @PathVariable Long id, @RequestBody Map<String, Object> req,
            @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(graphService.createRelation(id, req, viewer(principal)));
    }

    @PutMapping("/{id}/graph/relations/{relationId}")
    public ApiResponse<com.platform.knowledge.entity.KnowledgeGraphRelation> updateRelation(
            @PathVariable Long id, @PathVariable Long relationId, @RequestBody Map<String, Object> req,
            @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(graphService.updateRelation(id, relationId, req, viewer(principal)));
    }

    @DeleteMapping("/{id}/graph/relations/{relationId}")
    public ApiResponse<Void> deleteRelation(@PathVariable Long id, @PathVariable Long relationId,
                                            @AuthenticationPrincipal JwtPrincipal principal) {
        graphService.deleteRelation(id, relationId, viewer(principal));
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/search")
    public ApiResponse<List<Map<String, Object>>> search(@PathVariable Long id,
                                                         @Valid @RequestBody SearchRequest req,
                                                         @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.search(id, req.query(), req.topK(), req.scoreThreshold(),
                req.mode(), req.effectiveAfter(), req.effectiveBefore(), req.documentType(),
                req.sectionType(), req.regulatoryCategory(), req.chunkLevel(), req.minRelevance(),
                viewer(principal)));
    }
}
