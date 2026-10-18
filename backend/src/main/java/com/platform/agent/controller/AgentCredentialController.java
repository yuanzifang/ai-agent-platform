package com.platform.agent.controller;

import com.platform.agent.service.AgentCredentialService;
import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Agent 凭证接口（需求 3.2(6) / P1）：列表/创建/编辑/删除；内容仅加密存储，不回显明文。
 */
@RestController
@RequestMapping("/api/v1/agent/{agentId}/credentials")
@RequiredArgsConstructor
public class AgentCredentialController {

    private final AgentCredentialService service;

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
}
