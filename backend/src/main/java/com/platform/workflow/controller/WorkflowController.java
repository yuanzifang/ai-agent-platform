package com.platform.workflow.controller;

import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import com.platform.workflow.service.WorkflowService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Agent 工作流与人工审批（HITL）接口。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    @GetMapping("/workflow/templates")
    public ApiResponse<List<Map<String, Object>>> templates() {
        return ApiResponse.ok(workflowService.templates());
    }

    @GetMapping("/agent/{agentId}/workflow")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long agentId) {
        return ApiResponse.ok(workflowService.get(agentId));
    }

    public record SaveRequest(String name, String status, Map<String, Object> definition) {
    }

    @PutMapping("/agent/{agentId}/workflow")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> save(@PathVariable Long agentId,
                                                 @RequestBody SaveRequest req,
                                                 @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(workflowService.save(agentId,
                Map.of("name", req.name() == null ? "" : req.name(),
                        "status", req.status() == null ? "ENABLED" : req.status(),
                        "definition", req.definition() == null ? Map.of() : req.definition()),
                principal));
    }

    @PostMapping("/agent/{agentId}/workflow/publish")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> publish(@PathVariable Long agentId,
                                                    @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(workflowService.publish(agentId, principal));
    }

    @GetMapping("/agent/{agentId}/workflow/versions")
    public ApiResponse<List<com.platform.workflow.entity.AgentWorkflowVersion>> versions(@PathVariable Long agentId) {
        return ApiResponse.ok(workflowService.versions(agentId));
    }

    @PostMapping("/agent/{agentId}/workflow/versions/{version}/rollback")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> rollback(@PathVariable Long agentId,
                                                     @PathVariable String version,
                                                     @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(workflowService.rollback(agentId, version, principal));
    }

    @DeleteMapping("/agent/{agentId}/workflow")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Void> delete(@PathVariable Long agentId, @AuthenticationPrincipal JwtPrincipal principal) {
        workflowService.delete(agentId, principal);
        return ApiResponse.ok();
    }

    public record RunRequest(@NotBlank String input) {
    }

    @PostMapping("/agent/{agentId}/workflow/run")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> run(@PathVariable Long agentId,
                                                @Valid @RequestBody RunRequest req,
                                                @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(workflowService.run(agentId, req.input(), principal));
    }

    @GetMapping("/agent/{agentId}/workflow/runs")
    public ApiResponse<Map<String, Object>> runs(@PathVariable Long agentId,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(workflowService.runs(agentId, page, pageSize));
    }

    @GetMapping("/workflow/runs/{runId}")
    public ApiResponse<Map<String, Object>> runDetail(@PathVariable Long runId) {
        return ApiResponse.ok(workflowService.runDetail(runId));
    }

    @GetMapping("/workflow/runs/{runId}/checkpoints")
    public ApiResponse<List<com.platform.workflow.entity.WorkflowCheckpoint>> checkpoints(
            @PathVariable Long runId) {
        return ApiResponse.ok(workflowService.checkpoints(runId));
    }

    @PostMapping("/workflow/runs/{runId}/resume")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> resume(@PathVariable Long runId,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(workflowService.resume(runId, principal));
    }

    @GetMapping("/workflow/approvals")
    public ApiResponse<Map<String, Object>> approvals(@RequestParam(required = false) String status,
                                                      @RequestParam(defaultValue = "1") long page,
                                                      @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(workflowService.approvals(status, page, pageSize));
    }

    /** 手动触发审批超时扫描（运维/测试）。 */
    @PostMapping("/workflow/approvals/expire-scan")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> expireScan() {
        return ApiResponse.ok(Map.of("expired", workflowService.scanExpiredApprovals()));
    }

    public record DecideRequest(boolean approve, String comment) {
    }

    @PostMapping("/workflow/approvals/{id}/decide")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> decide(@PathVariable Long id,
                                                   @RequestBody DecideRequest req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(workflowService.decide(id, req.approve(), req.comment(), principal));
    }
}
