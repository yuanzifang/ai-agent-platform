package com.platform.agent.controller;

import com.platform.agent.entity.AgentScheduleResult;
import com.platform.agent.service.AgentScheduleService;
import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Agent 定时调度接口（需求 3.2(5) / P1）。
 */
@RestController
@RequestMapping("/api/v1/agent/{agentId}/schedules")
@RequiredArgsConstructor
public class AgentScheduleController {

    private final AgentScheduleService service;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@PathVariable Long agentId) {
        return ApiResponse.ok(service.list(agentId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> create(@PathVariable Long agentId,
                                                   @RequestBody Map<String, Object> req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(service.create(agentId, req, principal));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Void> update(@PathVariable Long agentId, @PathVariable Long id,
                                    @RequestBody Map<String, Object> req,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        service.update(agentId, id, req, principal);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Void> delete(@PathVariable Long agentId, @PathVariable Long id,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        service.delete(agentId, id, principal);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/results")
    public ApiResponse<List<AgentScheduleResult>> results(@PathVariable Long agentId, @PathVariable Long id) {
        return ApiResponse.ok(service.results(agentId, id));
    }

    /** 立即执行一次（调试；不改变下次执行时间）。 */
    @PostMapping("/{id}/run")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> run(@PathVariable Long agentId, @PathVariable Long id) {
        return ApiResponse.ok(service.runNow(agentId, id));
    }
}
