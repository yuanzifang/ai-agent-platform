package com.platform.system.controller;

import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.PlatformAdminService;
import com.platform.system.service.PlatformInfraConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 平台基础设施配置（仅平台管理员；保存后需重启生效）。 */
@RestController
@RequestMapping("/api/v1/platform/infrastructure")
@RequiredArgsConstructor
public class PlatformInfraController {

    private final PlatformAdminService platformAdminService;
    private final PlatformInfraConfigService configService;
    private final com.platform.system.service.SearchOutboxStatusService outboxStatusService;

    @GetMapping
    public ApiResponse<Map<String, Object>> get(@AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        return ApiResponse.ok(configService.current());
    }

    @GetMapping("/search-outbox")
    public ApiResponse<Map<String, Object>> outbox(@AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        return ApiResponse.ok(outboxStatusService.status());
    }

    @org.springframework.web.bind.annotation.PostMapping("/search-outbox/retry-failed")
    public ApiResponse<Map<String, Object>> retryFailedOutbox(@AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        return ApiResponse.ok(Map.of("retried", outboxStatusService.retryFailed()));
    }

    @org.springframework.web.bind.annotation.PostMapping("/test")
    public ApiResponse<Map<String, Object>> test(@RequestBody Map<String, Object> req,
                                                 @AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        return ApiResponse.ok(configService.test(req));
    }

    @PutMapping
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        return ApiResponse.ok(configService.save(req));
    }
}
