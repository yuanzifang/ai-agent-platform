package com.platform.auth.controller;

import com.platform.auth.service.AuthService;
import com.platform.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（需求文档 7.2 / 7.3 字段定义）。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    public record LoginRequest(@NotBlank String username, @NotBlank String password, String tenantKey) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record LoginResponse(String accessToken, String refreshToken, long expiresIn, UserInfo user,
                                Boolean mfaRequired, String mfaToken) {
    }

    public record UserInfo(String id, String displayName, boolean isPlatformAdmin, String tenantId,
                           java.util.List<String> roles, java.util.List<String> permissions) {
    }

    public record RefreshResponse(String accessToken, long expiresIn) {
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        AuthService.LoginResult r = authService.login(req.username(), req.password(),
                req.tenantKey(), request);
        if (r.mfaRequired()) {
            return ApiResponse.ok(new LoginResponse(null, null, 0, null, true, r.mfaToken()));
        }
        return ApiResponse.ok(new LoginResponse(r.accessToken(), r.refreshToken(), r.expiresIn(),
                new UserInfo(r.userId(), r.displayName(), r.platformAdmin(),
                        r.tenantId() == null ? null : String.valueOf(r.tenantId()),
                        r.roles(), r.permissions()), false, null));
    }

    public record MfaVerifyRequest(@NotBlank String mfaToken, @NotBlank String code) {
    }

    public record MfaCodeRequest(String code) {
    }

    /** 登录第二步：MFA 动态码/恢复码校验（使用短期 mfaToken，无需 JWT）。 */
    @PostMapping("/mfa/verify")
    public ApiResponse<LoginResponse> verifyMfa(@Valid @RequestBody MfaVerifyRequest req,
                                                HttpServletRequest request) {
        AuthService.LoginResult r = authService.verifyMfa(req.mfaToken(), req.code(), request);
        return ApiResponse.ok(new LoginResponse(r.accessToken(), r.refreshToken(), r.expiresIn(),
                new UserInfo(r.userId(), r.displayName(), r.platformAdmin(),
                        r.tenantId() == null ? null : String.valueOf(r.tenantId()),
                        r.roles(), r.permissions()), false, null));
    }

    @GetMapping("/mfa/status")
    public ApiResponse<java.util.Map<String, Object>> mfaStatus(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            com.platform.security.JwtPrincipal principal) {
        return ApiResponse.ok(authService.mfaStatus(principal.userId()));
    }

    /** 初始化 MFA：返回一次性密钥与 otpauth URI（此时尚未启用）。 */
    @PostMapping("/mfa/setup")
    public ApiResponse<java.util.Map<String, Object>> mfaSetup(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            com.platform.security.JwtPrincipal principal) {
        return ApiResponse.ok(authService.mfaSetup(principal.userId()));
    }

    /** 校验动态码后启用 MFA，返回 10 个一次性恢复码（仅本次）。 */
    @PostMapping("/mfa/enable")
    public ApiResponse<java.util.Map<String, Object>> mfaEnable(@RequestBody MfaCodeRequest req,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            com.platform.security.JwtPrincipal principal) {
        java.util.List<String> codes = authService.mfaEnable(principal.userId(), req.code());
        return ApiResponse.ok(java.util.Map.of("recoveryCodes", codes));
    }

    @PostMapping("/mfa/disable")
    public ApiResponse<Void> mfaDisable(@RequestBody MfaCodeRequest req,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            com.platform.security.JwtPrincipal principal) {
        authService.mfaDisable(principal.userId(), req.code());
        return ApiResponse.ok();
    }

    @PostMapping("/refresh")
    public ApiResponse<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest req) {
        return ApiResponse.ok(new RefreshResponse(authService.refresh(req.refreshToken()), 7200));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody RefreshRequest req) {
        authService.logout(req.refreshToken());
        return ApiResponse.ok();
    }
}
