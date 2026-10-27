package com.platform.deploy.controller;

import com.platform.common.response.ApiResponse;
import com.platform.deploy.service.AgentDeploymentService;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Agent 容器部署（发布 → 部署 → 运行）。
 * MVP 目标为「本机 Docker」：一个已发布 Agent 对应一个运行时容器，暴露 OpenAI 兼容接口。
 */
@RestController
@RequestMapping("/api/v1/agent/{agentId}/deployment")
@RequiredArgsConstructor
@io.swagger.v3.oas.annotations.tags.Tag(name = "Agent 部署", description = "把已发布 Agent 部署为独立容器并暴露 OpenAI 兼容接口")
public class AgentDeploymentController {

    private final AgentDeploymentService deploymentService;

    @GetMapping
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> status(@PathVariable Long agentId) {
        return ApiResponse.ok(deploymentService.status(agentId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> deploy(@PathVariable Long agentId,
                                                   @RequestBody(required = false) Map<String, Object> body,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        Integer replicas = body != null && body.get("replicas") instanceof Number n ? n.intValue() : null;
        return ApiResponse.ok(deploymentService.deploy(agentId, replicas, principal));
    }

    @PostMapping("/start")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> start(@PathVariable Long agentId,
                                                  @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(deploymentService.start(agentId, principal));
    }

    @PostMapping("/stop")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> stop(@PathVariable Long agentId,
                                                 @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(deploymentService.stop(agentId, principal));
    }

    @DeleteMapping
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Void> remove(@PathVariable Long agentId,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        deploymentService.remove(agentId, principal);
        return ApiResponse.ok();
    }

    @GetMapping("/token")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> token(@PathVariable Long agentId) {
        return ApiResponse.ok(Map.of("token", deploymentService.runtimeToken(agentId)));
    }

    @PostMapping("/token/rotate")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> rotateToken(@PathVariable Long agentId,
                                                        @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(deploymentService.rotateToken(agentId, principal));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> stats(@PathVariable Long agentId) {
        return ApiResponse.ok(deploymentService.stats(agentId));
    }

    @GetMapping("/history")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<List<Map<String, Object>>> history(@PathVariable Long agentId,
                                                          @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(deploymentService.history(agentId, limit));
    }

    @GetMapping("/logs")
    @PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> logs(@PathVariable Long agentId,
                                                 @RequestParam(defaultValue = "200") int tail,
                                                 @RequestParam(defaultValue = "false") boolean all) {
        return ApiResponse.ok(Map.of("logs", deploymentService.logs(agentId, tail, all)));
    }
}
