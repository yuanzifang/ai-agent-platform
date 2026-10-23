package com.platform.agent.controller;

import com.platform.agent.service.McpServerService;
import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * MCP Server 管理接口（需求 3.15 / P1）。
 */
@RestController
@RequestMapping("/api/v1/mcp-servers")
@RequiredArgsConstructor

@io.swagger.v3.oas.annotations.tags.Tag(name = "MCP Server")
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

    @GetMapping("/{id}/resources/read")
    @PreAuthorize("hasAuthority('tool:manage')")
    public ApiResponse<Map<String, Object>> readResource(@PathVariable Long id,
                                                        @RequestParam String uri) {
        return ApiResponse.ok(service.readResource(id, uri));
    }

    public record PromptRequest(String name, Map<String, Object> arguments) {
    }

    @PostMapping("/{id}/prompts/get")
    @PreAuthorize("hasAuthority('tool:manage')")
    public ApiResponse<Map<String, Object>> getPrompt(@PathVariable Long id,
                                                      @RequestBody PromptRequest req) {
        return ApiResponse.ok(service.getPrompt(id, req.name(), req.arguments()));
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

    @GetMapping("/{id}/tools/diff")
    @PreAuthorize("hasAuthority('tool:manage')")
    public ApiResponse<Map<String, Object>> toolDiff(@PathVariable Long id) {
        return ApiResponse.ok(service.toolDiff(id));
    }

    @GetMapping("/{id}/tools")
    public ApiResponse<Map<String, Object>> tools(@PathVariable Long id) {
        return ApiResponse.ok(service.tools(id));
    }

    @GetMapping("/{id}/references")
    public ApiResponse<Map<String, Object>> references(@PathVariable Long id) {
        return ApiResponse.ok(service.references(id));
    }

    // ---------- OAuth 2.1 ----------

    @PostMapping("/{id}/oauth/authorize")
    @PreAuthorize("hasAuthority('tool:manage')")
    public ApiResponse<Map<String, Object>> oauthAuthorize(@PathVariable Long id,
                                                           @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(service.oauthAuthorize(id, principal));
    }

    /** OAuth 回调由浏览器直接跳转（无 JWT），用一次性 state 防伪。 */
    @GetMapping("/oauth/callback")
    public ResponseEntity<Void> oauthCallback(@RequestParam(required = false) String code,
                                              @RequestParam(required = false) String state,
                                              @RequestParam(required = false) String error,
                                              @RequestParam(name = "error_description", required = false) String errorDescription) {
        String target = service.oauthCallback(code, state, error, errorDescription);
        return ResponseEntity.status(302).location(URI.create(target)).build();
    }

    @GetMapping("/{id}/oauth/status")
    public ApiResponse<Map<String, Object>> oauthStatus(@PathVariable Long id) {
        return ApiResponse.ok(service.oauthStatus(id));
    }

    @PostMapping("/{id}/oauth/disconnect")
    @PreAuthorize("hasAuthority('tool:manage')")
    public ApiResponse<Void> oauthDisconnect(@PathVariable Long id,
                                             @AuthenticationPrincipal JwtPrincipal principal) {
        service.oauthDisconnect(id, principal);
        return ApiResponse.ok();
    }
}
