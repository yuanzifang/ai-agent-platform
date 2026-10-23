package com.platform.auth.controller;

import com.platform.auth.service.AuthCookieService;
import com.platform.auth.service.AuthService;
import com.platform.common.exception.BusinessException;
import com.platform.common.response.ApiResponse;
import com.platform.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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

@io.swagger.v3.oas.annotations.tags.Tag(name = "认证")
public class AuthController {

    private final AuthService authService;
    private final AuthCookieService cookieService;
    private final JwtService jwtService;

    public record LoginRequest(@NotBlank String username, @NotBlank String password, String tenantKey) {
    }

    public record RefreshRequest(String refreshToken) {
    }

    public record LoginResponse(String accessToken, long expiresIn, UserInfo user,
                                Boolean mfaRequired, String mfaToken) {
    }

    public record UserInfo(String id, String displayName, boolean isPlatformAdmin, String tenantId,
                           java.util.List<String> roles, java.util.List<String> permissions) {
    }

    public record RefreshResponse(String accessToken, long expiresIn) {
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest req,
                                                            HttpServletRequest request) {
        AuthService.LoginResult r = authService.login(req.username(), req.password(),
                req.tenantKey(), request);
        if (r.mfaRequired()) {
            return ResponseEntity.ok(ApiResponse.ok(
                    new LoginResponse(null, 0, null, true, r.mfaToken())));
        }
        LoginResponse body = new LoginResponse(r.accessToken(), r.expiresIn(),
                new UserInfo(r.userId(), r.displayName(), r.platformAdmin(),
                        r.tenantId() == null ? null : String.valueOf(r.tenantId()),
                        r.roles(), r.permissions()), false, null);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookieService.create(r.refreshToken()).toString())
                .body(ApiResponse.ok(body));
    }

    public record MfaVerifyRequest(@NotBlank String mfaToken, @NotBlank String code) {
    }

    public record MfaCodeRequest(String code) {
    }

    /** 登录第二步：MFA 动态码/恢复码校验（使用短期 mfaToken，无需 JWT）。 */
    @PostMapping("/mfa/verify")
    public ResponseEntity<ApiResponse<LoginResponse>> verifyMfa(@Valid @RequestBody MfaVerifyRequest req,
                                                                HttpServletRequest request) {
        AuthService.LoginResult r = authService.verifyMfa(req.mfaToken(), req.code(), request);
        LoginResponse body = new LoginResponse(r.accessToken(), r.expiresIn(),
                new UserInfo(r.userId(), r.displayName(), r.platformAdmin(),
                        r.tenantId() == null ? null : String.valueOf(r.tenantId()),
                        r.roles(), r.permissions()), false, null);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookieService.create(r.refreshToken()).toString())
                .body(ApiResponse.ok(body));
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

    public record ChangePasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword) {
    }

    public record ForgotPasswordRequest(String username) {
    }

    public record ResetPasswordRequest(@NotBlank String token, @NotBlank String newPassword) {
    }

    /** 登录后自助修改密码（P0）。 */
    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@RequestBody ChangePasswordRequest req,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            com.platform.security.JwtPrincipal principal) {
        authService.changePassword(principal.userId(), req.oldPassword(), req.newPassword());
        return ApiResponse.ok();
    }

    /** 忘记密码：生成一次性 Token（SMTP 未配置时写入后端日志，防账号枚举统一成功）。 */
    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgotPassword(@RequestBody ForgotPasswordRequest req) {
        authService.requestPasswordReset(req.username());
        return ApiResponse.ok();
    }

    /** 重置密码：Token + 新密码。 */
    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@RequestBody ResetPasswordRequest req) {
        authService.resetPassword(req.token(), req.newPassword());
        return ApiResponse.ok();
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshResponse>> refresh(
            @RequestBody(required = false) RefreshRequest req, HttpServletRequest request) {
        String token = cookieService.resolve(req == null ? null : req.refreshToken(), request);
        if (token == null || token.isBlank()) {
            throw new BusinessException(401, "缺少刷新令牌，请重新登录");
        }
        String accessToken = authService.refresh(token);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookieService.create(token).toString())
                .body(ApiResponse.ok(new RefreshResponse(accessToken, jwtService.getExpirySeconds())));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@RequestBody(required = false) RefreshRequest req,
                                                    HttpServletRequest request) {
        String token = cookieService.resolve(req == null ? null : req.refreshToken(), request);
        if (token != null && !token.isBlank()) authService.logout(token);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookieService.clear().toString())
                .body(ApiResponse.ok());
    }
}
