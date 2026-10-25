package com.platform.gateway.controller;

import com.platform.common.response.ApiResponse;
import com.platform.gateway.entity.GatewayRequestLog;
import com.platform.gateway.service.GatewayService;
import com.platform.security.JwtPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/gateway")
@RequiredArgsConstructor
@io.swagger.v3.oas.annotations.tags.Tag(name = "开放平台网关")
public class GatewayController {

    private final GatewayService gatewayService;

    @GetMapping("/routes")
    @PreAuthorize("hasAuthority('tenant:manage')")
    public ApiResponse<List<Map<String, Object>>> routes() {
        return ApiResponse.ok(gatewayService.list());
    }

    @PostMapping("/routes")
    @PreAuthorize("hasAuthority('tenant:manage')")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(gatewayService.create(req, principal));
    }

    @PutMapping("/routes/{id}")
    @PreAuthorize("hasAuthority('tenant:manage')")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @RequestBody Map<String, Object> req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(gatewayService.update(id, req, principal));
    }

    @PostMapping("/routes/{id}/rotate-key")
    @PreAuthorize("hasAuthority('tenant:manage')")
    public ApiResponse<Map<String, Object>> rotate(@PathVariable Long id,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(gatewayService.rotateKey(id, principal));
    }

    @DeleteMapping("/routes/{id}")
    @PreAuthorize("hasAuthority('tenant:manage')")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        gatewayService.delete(id, principal);
        return ApiResponse.ok();
    }

    @GetMapping("/routes/{id}/logs")
    @PreAuthorize("hasAuthority('tenant:manage')")
    public ApiResponse<List<GatewayRequestLog>> logs(@PathVariable Long id) {
        return ApiResponse.ok(gatewayService.logs(id));
    }

    // ---------- 调用方 API Key ----------

    @GetMapping("/routes/{id}/keys")
    @PreAuthorize("hasAuthority('tenant:manage')")
    public ApiResponse<List<Map<String, Object>>> keys(@PathVariable Long id) {
        return ApiResponse.ok(gatewayService.apiKeys(id));
    }

    @PostMapping("/routes/{id}/keys")
    @PreAuthorize("hasAuthority('tenant:manage')")
    public ApiResponse<Map<String, Object>> createKey(@PathVariable Long id,
                                                      @RequestBody Map<String, Object> req,
                                                      @AuthenticationPrincipal JwtPrincipal principal) {
        java.time.LocalDateTime expiresAt = req.get("expiresAt") == null ? null
                : java.time.LocalDateTime.parse(String.valueOf(req.get("expiresAt")));
        var created = gatewayService.createKey(id, req, principal, expiresAt);
        return ApiResponse.ok(created);
    }

    @PutMapping("/routes/{id}/keys/{keyId}")
    @PreAuthorize("hasAuthority('tenant:manage')")
    public ApiResponse<Void> updateKey(@PathVariable Long id, @PathVariable Long keyId,
                                       @RequestBody Map<String, Object> req,
                                       @AuthenticationPrincipal JwtPrincipal principal) {
        gatewayService.updateKey(keyId, req, principal);
        return ApiResponse.ok();
    }

    @DeleteMapping("/routes/{id}/keys/{keyId}")
    @PreAuthorize("hasAuthority('tenant:manage')")
    public ApiResponse<Void> deleteKey(@PathVariable Long id, @PathVariable Long keyId,
                                       @AuthenticationPrincipal JwtPrincipal principal) {
        gatewayService.deleteApiKey(keyId, principal);
        return ApiResponse.ok();
    }

    /** 公开代理入口：/api/v1/gateway/call/{routeKey}/...，X-Gateway-Key 鉴权。 */
    @RequestMapping("/call/{routeKey}/**")
    public StreamingResponseBody call(@PathVariable String routeKey, HttpServletRequest request,
                                       HttpServletResponse response) {
        return gatewayService.proxy(routeKey, request, response);
    }
}
