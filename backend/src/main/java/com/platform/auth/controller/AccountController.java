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
