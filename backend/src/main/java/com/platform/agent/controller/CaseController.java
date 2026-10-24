package com.platform.agent.controller;

import com.platform.agent.service.CaseService;
import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 对话用例库：经典/坏例沉淀、审核、导出评测集、候选模型回归。 */
@RestController
@RequestMapping("/api/v1/cases")
@RequiredArgsConstructor
public class CaseController {

    private final CaseService service;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String source,
                                                 @RequestParam(required = false) Long agentId,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String tier,
                                                 @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(service.list(principal, status, source, agentId, keyword, tier));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(service.create(req, principal));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @RequestBody Map<String, Object> req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(service.update(id, req, principal));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        service.delete(id, principal);
        return ApiResponse.ok();
    }

    @PostMapping("/export")
    @PreAuthorize("hasAuthority('eval:manage')")
    public ApiResponse<Map<String, Object>> export(@RequestBody Map<String, Object> req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(service.export(req, principal));
    }

    @PostMapping("/run")
    @PreAuthorize("hasAuthority('eval:manage')")
    public ApiResponse<Map<String, Object>> run(@RequestBody Map<String, Object> req,
                                                @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(service.run(req, principal));
    }

    @GetMapping("/calibrations")
    @PreAuthorize("hasAuthority('eval:manage')")
    public ApiResponse<Map<String, Object>> calibrations(@RequestParam(required = false) String status,
                                                         @RequestParam(required = false) Long runId,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "20") int pageSize,
                                                         @RequestParam(defaultValue = "false") boolean blind,
                                                         @RequestParam(defaultValue = "false") boolean mine,
                                                         @RequestParam(defaultValue = "false") boolean sampledOnly,
                                                         @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(service.calibrations(principal, status, runId, page, pageSize, blind, mine, sampledOnly));
    }

    @PostMapping("/calibrations/sample")
    @PreAuthorize("hasAuthority('eval:manage')")
    public ApiResponse<Map<String, Object>> sampleCalibrations(@RequestBody(required = false) Map<String, Object> req,
                                                               @AuthenticationPrincipal JwtPrincipal principal) {
        int percent = req == null || req.get("percent") == null ? 20 : ((Number) req.get("percent")).intValue();
        return ApiResponse.ok(service.sampleCalibrations(percent, principal));
    }

    @PutMapping("/calibrations/{id}/arbitrate")
    @PreAuthorize("hasAuthority('eval:manage')")
    public ApiResponse<Map<String, Object>> arbitrate(@PathVariable Long id,
                                                      @RequestBody Map<String, Object> req,
                                                      @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(service.arbitrate(id, req, principal));
    }

    @PutMapping("/calibrations/{id}/assign")
    @PreAuthorize("hasAuthority('eval:manage')")
    public ApiResponse<Map<String, Object>> assignCalibration(@PathVariable Long id,
                                                              @RequestBody(required = false) Map<String, Object> req,
                                                              @AuthenticationPrincipal JwtPrincipal principal) {
        Long assigneeId = req == null || req.get("assigneeId") == null ? null
                : ((Number) req.get("assigneeId")).longValue();
        return ApiResponse.ok(service.assignCalibration(id, assigneeId, principal));
    }

    @GetMapping("/calibrations/stats")
    @PreAuthorize("hasAuthority('eval:manage')")
    public ApiResponse<Map<String, Object>> calibrationStats(@AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(service.calibrationStats(principal));
    }

    @PutMapping("/calibrations/{id}")
    @PreAuthorize("hasAuthority('eval:manage')")
    public ApiResponse<Map<String, Object>> calibrate(@PathVariable Long id,
                                                      @RequestBody Map<String, Object> req,
                                                      @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(service.calibrate(id, req, principal));
    }

    @GetMapping("/runs")
    public ApiResponse<Map<String, Object>> runs(@AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(service.runs(principal));
    }

    @GetMapping("/runs/{id}")
    public ApiResponse<Map<String, Object>> runDetail(@PathVariable Long id,
                                                      @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(service.runDetail(id, principal));
    }
}
