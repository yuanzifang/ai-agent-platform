package com.platform.deploy.controller;

import com.platform.common.response.ApiResponse;
import com.platform.deploy.service.AgentRuntimeKeyService;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 部署容器的调用方 Key（按 Key 日配额）。 */
@RestController
@RequestMapping("/api/v1/agent/{agentId}/runtime-keys")
@RequiredArgsConstructor
@io.swagger.v3.oas.annotations.tags.Tag(name = "Agent 运行时 Key", description = "按调用方 Key 的日配额与用量")
public class AgentRuntimeKeyController {

    private final AgentRuntimeKeyService keyService;

    @GetMapping
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<List<Map<String, Object>>> list(@PathVariable Long agentId) {
        return ApiResponse.ok(keyService.list(agentId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> create(@PathVariable Long agentId,
                                                   @RequestBody Map<String, Object> req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(keyService.create(agentId, req, principal));
    }

    @PutMapping("/{keyId}")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long agentId, @PathVariable Long keyId,
                                                   @RequestBody Map<String, Object> req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(keyService.update(agentId, keyId, req, principal));
    }

    @DeleteMapping("/{keyId}")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Void> delete(@PathVariable Long agentId, @PathVariable Long keyId,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        keyService.delete(agentId, keyId, principal);
        return ApiResponse.ok();
    }
}
