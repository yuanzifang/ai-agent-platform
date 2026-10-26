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

@io.swagger.v3.oas.annotations.tags.Tag(name = "评测")
public class EvalController {

    private final EvalService evalService;

    // ---------- 数据集 ----------

    @GetMapping("/step-types")
    public ApiResponse<List<Map<String, Object>>> stepTypes() {
        return ApiResponse.ok(com.platform.eval.service.EvalStepCatalog.definitions());
    }

    /** 跨数据集步骤覆盖矩阵，可按 Agent 过滤。 */
    @GetMapping("/datasets/{id}/trend")
    public ApiResponse<Map<String, Object>> datasetTrend(@PathVariable Long id,
                                                          @RequestParam(required = false) Long agentId,
                                                          @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(evalService.datasetTrend(id, agentId, limit));
    }

    @GetMapping("/tasks/{id}/compare/{baselineId}")
    public ApiResponse<Map<String, Object>> compareTasks(@PathVariable Long id,
                                                          @PathVariable Long baselineId) {
        return ApiResponse.ok(evalService.compareTasks(id, baselineId));
    }

    @GetMapping("/coverage")
    public ApiResponse<Map<String, Object>> coverage(@RequestParam(required = false) Long agentId) {
        return ApiResponse.ok(evalService.coverage(agentId));
    }

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
        return ApiResponse.ok(evalService.items(id).stream().map(i -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", i.getId());
            row.put("question", i.getQuestion());
            row.put("expectedAnswer", i.getExpectedAnswer() == null ? "" : i.getExpectedAnswer());
            row.put("scoreCriteria", i.getScoreCriteria() == null ? "" : i.getScoreCriteria());
            row.put("stepType", i.getStepType() == null ? "ANSWER" : i.getStepType());
            row.put("expectedPayload", i.getExpectedPayload());
            row.put("stepConfig", i.getStepConfig());
            return row;
        }).toList());
    }

    @PostMapping("/datasets/{id}/items")
    @PreAuthorize("hasAuthority('eval:manage')")
    public ApiResponse<Void> addItem(@PathVariable Long id, @Valid @RequestBody ItemRequest req,
                                     @AuthenticationPrincipal JwtPrincipal principal) {
        evalService.addItem(id, req.question(), req.expectedAnswer(), req.scoreCriteria(),
                req.stepType(), req.expectedPayload(), req.stepConfig(), principal);
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

    /** 在线监控：抽样线上 SUCCESS Trace 评测并回填 agent_trace.score（需求 3.5）。 */
    @PostMapping("/online")
    @PreAuthorize("hasAuthority('eval:manage')")
    public ApiResponse<Map<String, Object>> runOnline(@RequestParam Long agentId,
                                                      @RequestParam(defaultValue = "10") int sampleSize,
                                                      @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(evalService.runOnlineMonitoring(agentId, sampleSize, principal));
    }

    /** 手动触发预发布评测（需求 3.5：发布前或手动触发；异步任务，不阻断）。 */
    @PostMapping("/pre-release")
    @PreAuthorize("hasAuthority('eval:manage')")
    public ApiResponse<Map<String, Object>> runPreRelease(@RequestParam Long agentId,
                                                          @RequestParam(required = false) Long datasetId,
                                                          @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(evalService.runPreReleaseManual(agentId, datasetId, principal));
    }

    /** 单步即时评测：不创建任务，直接调检索/重排/改写步骤并返回实际结果与明细。 */
    @PostMapping("/steps/run")
    @PreAuthorize("hasAuthority('eval:manage')")
    public ApiResponse<Map<String, Object>> runStep(@Valid @RequestBody StepRunRequest req,
                                                    @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(evalService.runStep(req.stepType(), req.query(),
                req.expectedPayload(), req.stepConfig(), principal));
    }

    /** 数据集步骤类型覆盖统计：用于发现缺少改写/检索/重排/回答集的缺口。 */
    @GetMapping("/datasets/{id}/step-summary")
    public ApiResponse<Map<String, Object>> stepSummary(@PathVariable Long id) {
        return ApiResponse.ok(evalService.stepSummary(id));
    }

    public record StepRunRequest(@NotBlank String stepType, @NotBlank String query,
                                 String expectedPayload, String stepConfig) {
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

    public record ItemRequest(@NotBlank String question, String expectedAnswer, String scoreCriteria,
                              String stepType, String expectedPayload, String stepConfig) {
    }

    public record ImportRequest(@NotBlank String jsonl) {
    }

    public record TaskRequest(String name, @NotNull Long agentId, @NotNull Long datasetId,
                              String judgeModel, Double passThreshold) {
    }
}
