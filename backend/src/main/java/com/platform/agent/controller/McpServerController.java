package com.platform.agent.controller;

import com.platform.agent.service.McpServerService;
import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MCP Server 管理接口（需求 3.15 / P1）。
 */
@RestController
@RequestMapping("/api/v1/mcp-servers")
@RequiredArgsConstructor
public class McpServerController {

    private final McpServerService service;

    @GetMapping
    public ApiResponse<Map<String, Object>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('tool:manage')")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(service.create(req, principal));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('tool:manage')")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody Map<String, Object> req,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        service.update(id, req, principal);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('tool:manage')")
    public ApiResponse<Void> delete(@PathVariable Long id, @AuthenticationPrincipal JwtPrincipal principal) {
        service.delete(id, principal);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasAuthority('tool:manage')")
    public ApiResponse<Map<String, Object>> test(@PathVariable Long id) {
        return ApiResponse.ok(service.test(id));
    }

    @GetMapping("/{id}/discover")
    @PreAuthorize("hasAuthority('tool:manage')")
    public ApiResponse<Map<String, Object>> discover(@PathVariable Long id) {
        return ApiResponse.ok(service.discover(id));
    }

    public record SyncRequest(List<String> toolNames) {
    }

    @PostMapping("/{id}/sync")
    @PreAuthorize("hasAuthority('tool:manage')")
    public ApiResponse<Map<String, Object>> sync(@PathVariable Long id,
                                                 @RequestBody(required = false) SyncRequest req,
                                                 @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(service.sync(id, req == null ? null : req.toolNames(), principal));
    }

    @GetMapping("/{id}/tools")
    public ApiResponse<Map<String, Object>> tools(@PathVariable Long id) {
        return ApiResponse.ok(service.tools(id));
    }

    @GetMapping("/{id}/references")
    public ApiResponse<Map<String, Object>> references(@PathVariable Long id) {
        return ApiResponse.ok(service.references(id));
    }
}
