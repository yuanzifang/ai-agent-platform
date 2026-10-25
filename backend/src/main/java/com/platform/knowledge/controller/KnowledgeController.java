package com.platform.knowledge.controller;

import com.platform.common.response.ApiResponse;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.service.EmbeddingService;
import com.platform.knowledge.service.KnowledgePermissionService;
import com.platform.knowledge.service.KnowledgeGraphService;
import com.platform.knowledge.service.KnowledgeService;
import com.platform.security.JwtPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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

    private static KnowledgePermissionService.Viewer viewer(JwtPrincipal principal) {
        return KnowledgePermissionService.Viewer.of(principal.userId(), principal.roles());
    }

    public record CreateKbRequest(@NotBlank String name, String description, String visibility,
                                  String embeddingModel, Boolean graphEnabled) {
    }

    @PostMapping
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('kb:create')")
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody CreateKbRequest req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.create(req.name(), req.description(), req.visibility(),
                req.embeddingModel(), req.graphEnabled(), principal.userId()));
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

    public record UpdateKbRequest(@NotBlank String name, String description, String embeddingModel,
                                  Boolean graphEnabled, Map<String, Object> parseConfig) {
    }

    /** 编辑名称/描述（需编辑权限）。 */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody UpdateKbRequest req,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        knowledgeService.update(id, req.name(), req.description(), req.embeddingModel(),
                req.graphEnabled(), req.parseConfig(), viewer(principal));
        return ApiResponse.ok();
    }

    /** 可选的 embedding 模型（名称 + 维度），供知识库创建/编辑下拉。 */
    @GetMapping("/embedding-models")
    public ApiResponse<List<Map<String, Object>>> embeddingModels() {
        return ApiResponse.ok(embeddingService.availableModels());
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

    public record UploadRequest(@NotBlank String fileName, @NotNull Long sizeBytes, @NotBlank String contentType) {
    }

    @PostMapping("/{id}/documents")
    public ApiResponse<Map<String, Object>> requestUpload(@PathVariable Long id,
                                                          @Valid @RequestBody UploadRequest req,
                                                          @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.requestUpload(id, req.fileName(), req.sizeBytes(), viewer(principal)));
    }

    public record CallbackRequest(@NotBlank String objectName, String parseConfig) {
    }

    @PostMapping("/{id}/documents/callback")
    public ApiResponse<Map<String, Object>> callback(@PathVariable Long id,
                                                     @Valid @RequestBody CallbackRequest req,
                                                     @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.callback(id, req.objectName(), req.parseConfig(), viewer(principal)));
    }

    /** 重新解析（DONE/FAILED）：清理旧分段/向量/图片后按最新解析配置重跑。 */
    @PostMapping("/{id}/documents/{docId}/reparse")
    public ApiResponse<Map<String, Object>> reparse(@PathVariable Long id, @PathVariable Long docId,
                                                    @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.reparse(id, docId, viewer(principal)));
    }

    public record UrlImportRequest(@NotBlank String url) {
    }

    /** URL 导入（网页/PDF/文本），复用对象存储 + 异步解析。 */
    @PostMapping("/{id}/documents/url")
    public ApiResponse<Map<String, Object>> importUrl(@PathVariable Long id,
                                                      @Valid @RequestBody UrlImportRequest req,
                                                      @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.importUrl(id, req.url(), viewer(principal)));
    }

    @GetMapping("/{id}/documents")
    public ApiResponse<List<KnowledgeDocument>> documents(@PathVariable Long id,
                                                          @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.documents(id, viewer(principal)));
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

    public record SearchRequest(@NotBlank String query, Integer topK, Double scoreThreshold, String mode) {
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
        return ApiResponse.ok(graphService.commitPreview(id, req, viewer(principal)));
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
                req.mode(), viewer(principal)));
    }
}
