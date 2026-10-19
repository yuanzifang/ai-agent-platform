package com.platform.auth.controller;

import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.AccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 账号合规接口（需求 3.25(7)）：个人数据导出 + 注销。
 */
@RestController
@RequestMapping("/api/v1/auth/me")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    public record MeInfo(String id, String tenantId, boolean isPlatformAdmin,
                         java.util.List<String> roles, java.util.List<String> permissions) {
    }

    /** 当前登录态的角色/权限（前端启动时刷新，避免使用过期缓存）。 */
    @GetMapping
    public ApiResponse<MeInfo> me(@AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(new MeInfo(
                principal.userId() == null ? null : String.valueOf(principal.userId()),
                principal.tenantId() == null ? null : String.valueOf(principal.tenantId()),
                principal.platformAdmin(), principal.roles(), principal.permissions()));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal JwtPrincipal principal) {
        byte[] data = accountService.exportData(principal);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"my-data.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(data);
    }

    public record DeleteRequest(String password, String confirmText) {
    }

    @DeleteMapping
    public ApiResponse<Void> delete(@RequestBody DeleteRequest req,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        accountService.deleteAccount(principal, req.password(), req.confirmText());
        return ApiResponse.ok();
    }
}
