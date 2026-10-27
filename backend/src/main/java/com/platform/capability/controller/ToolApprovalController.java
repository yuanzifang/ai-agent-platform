package com.platform.capability.controller;

import com.platform.capability.entity.ToolApproval;
import com.platform.capability.service.ToolApprovalService;
import com.platform.capability.runtime.ToolRuntimeService;
import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** 写工具逐次审批：批准后才执行一次工具。 */
@RestController
@RequestMapping("/api/v1/tool-approvals")
@RequiredArgsConstructor
public class ToolApprovalController {

    private final ToolApprovalService approvalService;
    private final ToolRuntimeService toolRuntime;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String status,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(approvalService.list(status, page, pageSize));
    }

    public record DecideRequest(boolean approve, String comment) {
    }

    @PostMapping("/{id}/decide")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> decide(@PathVariable Long id,
                                                   @RequestBody DecideRequest req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        ToolApproval a = approvalService.claim(id, req.approve(), req.comment(), principal);
        Map<String, Object> view = view(a);
        if (req.approve()) {
            try {
                Map<String, Object> result = toolRuntime.executeToolForWorkflow(
                        a.getAgentId(), a.getToolId(), a.getToolInput());
                Object output = result.get("output");
                approvalService.finish(id, "EXECUTED", output == null ? "" : String.valueOf(output));
                view.put("status", "EXECUTED");
                view.put("result", output);
            } catch (Exception e) {
                approvalService.finish(id, "FAILED", e.getMessage());
                view.put("status", "FAILED");
                view.put("result", e.getMessage());
            }
        }
        return ApiResponse.ok(view);
    }

    private Map<String, Object> view(ToolApproval a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("agentId", a.getAgentId());
        m.put("toolId", a.getToolId());
        m.put("toolKey", a.getToolKey());
        m.put("toolName", a.getToolName());
        m.put("toolType", a.getToolType());
        m.put("toolInput", a.getToolInput());
        m.put("status", a.getStatus());
        m.put("comment", a.getComment());
        m.put("result", a.getResult());
        m.put("createdAt", a.getCreatedAt());
        m.put("decidedAt", a.getDecidedAt());
        return m;
    }
}
