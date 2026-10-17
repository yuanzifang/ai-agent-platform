package com.platform.eval.controller;

import com.platform.common.response.ApiResponse;
import com.platform.eval.service.EvalService;
import com.platform.security.JwtPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 评测接口（需求 3.5/7.2）：数据集管理与评测任务。
 */
@RestController
@RequestMapping("/api/v1/eval")
@RequiredArgsConstructor
public class EvalController {

    private final EvalService evalService;

    // ---------- 数据集 ----------

    @GetMapping("/datasets")
    public ApiResponse<List<Map<String, Object>>> datasets() {
        return ApiResponse.ok(evalService.datasets());
    }

    @PostMapping("/datasets")
    @PreAuthorize("hasAuthority('eval:manage')")
    public ApiResponse<Map<String, Object>> createDataset(@Valid @RequestBody DatasetRequest req,
                                                          @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(evalService.createDataset(req.name(), req.description(), req.agentId(), principal));
    }

    @DeleteMapping("/datasets/{id}")
    @PreAuthorize("hasAuthority('eval:manage')")
    public ApiResponse<Void> deleteDataset(@PathVariable Long id) {
        evalService.deleteDataset(id);
        return ApiResponse.ok();
    }

    @GetMapping("/datasets/{id}/items")
    public ApiResponse<List<Map<String, Object>>> items(@PathVariable Long id) {
        return ApiResponse.ok(evalService.items(id).stream().map(i -> Map.<String, Object>of(
                "id", i.getId(), "question", i.getQuestion(),
                "expectedAnswer", i.getExpectedAnswer() == null ? "" : i.getExpectedAnswer(),
                "scoreCriteria", i.getScoreCriteria() == null ? "" : i.getScoreCriteria())).toList());
    }

    @PostMapping("/datasets/{id}/items")
    @PreAuthorize("hasAuthority('eval:manage')")
    public ApiResponse<Void> addItem(@PathVariable Long id, @Valid @RequestBody ItemRequest req,
                                     @AuthenticationPrincipal JwtPrincipal principal) {
        evalService.addItem(id, req.question(), req.expectedAnswer(), req.scoreCriteria(), principal);
        return ApiResponse.ok();
    }

    @PostMapping("/datasets/{id}/items/import")
    @PreAuthorize("hasAuthority('eval:manage')")
    public ApiResponse<Map<String, Object>> importItems(@PathVariable Long id,
                                                        @Valid @RequestBody ImportRequest req,
                                                        @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(Map.of("imported", evalService.importItems(id, req.jsonl(), principal)));
    }

    @DeleteMapping("/datasets/items/{itemId}")
    @PreAuthorize("hasAuthority('eval:manage')")
    public ApiResponse<Void> deleteItem(@PathVariable Long itemId) {
        evalService.deleteItem(itemId);
        return ApiResponse.ok();
    }

    // ---------- 任务 ----------

    @GetMapping("/tasks")
    public ApiResponse<Map<String, Object>> tasks(@RequestParam(required = false) Long agentId) {
        return ApiResponse.ok(evalService.tasks(agentId));
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<Map<String, Object>> taskDetail(@PathVariable Long id) {
        return ApiResponse.ok(evalService.taskDetail(id));
    }

    @PostMapping("/tasks")
    @PreAuthorize("hasAuthority('eval:manage')")
    public ApiResponse<Map<String, Object>> createTask(@Valid @RequestBody TaskRequest req,
                                                       @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(evalService.createTask(req.name(), req.agentId(), req.datasetId(),
                req.judgeModel(), req.passThreshold(), principal));
    }

    public record DatasetRequest(@NotBlank String name, String description, Long agentId) {
    }

    public record ItemRequest(@NotBlank String question, String expectedAnswer, String scoreCriteria) {
    }

    public record ImportRequest(@NotBlank String jsonl) {
    }

    public record TaskRequest(String name, @NotNull Long agentId, @NotNull Long datasetId,
                              String judgeModel, Double passThreshold) {
    }
}
