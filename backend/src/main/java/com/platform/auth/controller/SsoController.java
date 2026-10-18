package com.platform.auth.controller;

import com.platform.auth.service.AuthService;
import com.platform.auth.service.SsoService;
import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSO / OIDC 接口（需求 3.25(5) / P1）：租户配置、可用性、授权跳转、回调换取 Token。
 */
@RestController
@RequestMapping("/api/v1/auth/sso")
@RequiredArgsConstructor
public class SsoController {

    private final SsoService ssoService;

    public record ExchangeRequest(@NotBlank String code, @NotBlank String state) {
    }

    @GetMapping("/available")
    public ApiResponse<Map<String, Object>> available(@RequestParam String tenantKey) {
        return ApiResponse.ok(ssoService.available(tenantKey));
    }

    @GetMapping("/authorize")
    public ApiResponse<Map<String, Object>> authorize(@RequestParam String tenantKey) {
        return ApiResponse.ok(ssoService.authorize(tenantKey));
    }

    @PostMapping("/exchange")
    public ApiResponse<Map<String, Object>> exchange(@RequestBody ExchangeRequest req,
                                                     HttpServletRequest request) {
        AuthService.LoginResult r = ssoService.exchange(req.code(), req.state(), request);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", r.userId());
        user.put("displayName", r.displayName());
        user.put("isPlatformAdmin", r.platformAdmin());
        user.put("tenantId", r.tenantId() == null ? null : String.valueOf(r.tenantId()));
        user.put("roles", r.roles());
        user.put("permissions", r.permissions());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accessToken", r.accessToken());
        data.put("refreshToken", r.refreshToken());
        data.put("expiresIn", r.expiresIn());
        data.put("user", user);
        data.put("mfaRequired", false);
        data.put("mfaToken", null);
        return ApiResponse.ok(data);
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('tenant:manage')")
    public ApiResponse<Map<String, Object>> settings() {
        return ApiResponse.ok(ssoService.settings());
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('tenant:manage')")
    public ApiResponse<Void> update(@RequestBody Map<String, Object> req,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        ssoService.update(req, principal);
        return ApiResponse.ok();
    }
}
