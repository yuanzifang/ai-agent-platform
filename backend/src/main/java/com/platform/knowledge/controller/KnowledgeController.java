package com.platform.knowledge.controller;

import com.platform.common.response.ApiResponse;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.entity.KnowledgeDocument;
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
 */
@RestController
@RequestMapping("/api/v1/knowledge-base")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public record CreateKbRequest(@NotBlank String name, String description) {
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody CreateKbRequest req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(knowledgeService.create(req.name(), req.description(), principal.userId()));
    }

    @GetMapping
    public ApiResponse<List<KnowledgeBase>> list() {
        return ApiResponse.ok(knowledgeService.list());
    }

    public record UploadRequest(@NotBlank String fileName, @NotNull Long sizeBytes, @NotBlank String contentType) {
    }

    @PostMapping("/{id}/documents")
    public ApiResponse<Map<String, Object>> requestUpload(@PathVariable Long id,
                                                          @Valid @RequestBody UploadRequest req) {
        return ApiResponse.ok(knowledgeService.requestUpload(id, req.fileName()));
    }

    public record CallbackRequest(@NotBlank String objectName) {
    }

    @PostMapping("/{id}/documents/callback")
    public ApiResponse<Map<String, Object>> callback(@PathVariable Long id,
                                                     @Valid @RequestBody CallbackRequest req) {
        return ApiResponse.ok(knowledgeService.callback(id, req.objectName()));
    }

    @GetMapping("/{id}/documents")
    public ApiResponse<List<KnowledgeDocument>> documents(@PathVariable Long id) {
        return ApiResponse.ok(knowledgeService.documents(id));
    }

    public record SearchRequest(@NotBlank String query, Integer topK, Double scoreThreshold) {
    }

    @PostMapping("/{id}/search")
    public ApiResponse<List<Map<String, Object>>> search(@PathVariable Long id,
                                                         @Valid @RequestBody SearchRequest req) {
        return ApiResponse.ok(knowledgeService.search(id, req.query(), req.topK(), req.scoreThreshold()));
    }
}
