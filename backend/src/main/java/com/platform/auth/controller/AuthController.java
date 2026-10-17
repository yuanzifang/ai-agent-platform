package com.platform.auth.controller;

import com.platform.auth.service.AuthService;
import com.platform.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
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

    public record LoginResponse(String accessToken, String refreshToken, long expiresIn, UserInfo user) {
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
        return ApiResponse.ok(new LoginResponse(r.accessToken(), r.refreshToken(), r.expiresIn(),
                new UserInfo(r.userId(), r.displayName(), r.platformAdmin(),
                        r.tenantId() == null ? null : String.valueOf(r.tenantId()),
                        r.roles(), r.permissions())));
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
