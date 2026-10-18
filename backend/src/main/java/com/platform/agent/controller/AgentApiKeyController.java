package com.platform.agent.controller;

import com.platform.agent.service.ApiKeyService;
import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Agent 集成 API Key 管理（需求 3.2(4)）：列表/创建/更新/删除；创建仅返回一次明文。
 */
@RestController
@RequestMapping("/api/v1/agent/{agentId}/api-keys")
@RequiredArgsConstructor
public class AgentApiKeyController {

    private final ApiKeyService apiKeyService;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@PathVariable Long agentId) {
        return ApiResponse.ok(apiKeyService.list(agentId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> create(@PathVariable Long agentId,
                                                   @RequestBody Map<String, Object> req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(apiKeyService.create(agentId, req, principal));
    }

    @PutMapping("/{keyId}")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Void> update(@PathVariable Long agentId, @PathVariable Long keyId,
                                    @RequestBody Map<String, Object> req,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        apiKeyService.update(agentId, keyId, req, principal);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{keyId}")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Void> delete(@PathVariable Long agentId, @PathVariable Long keyId,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        apiKeyService.delete(agentId, keyId, principal);
        return ApiResponse.ok();
    }
}
