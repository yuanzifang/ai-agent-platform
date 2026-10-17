package com.platform.system.controller;

import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.PlatformAdminService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 平台管理接口（需求 7.2）：仅平台管理员。
 */
@RestController
@RequestMapping("/api/v1/platform")
@RequiredArgsConstructor
public class PlatformAdminController {

    private final PlatformAdminService platformAdminService;

    @GetMapping("/tenants")
    public ApiResponse<List<Map<String, Object>>> tenants(@AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        return ApiResponse.ok(platformAdminService.tenants());
    }

    @PostMapping("/tenants")
    public ApiResponse<Map<String, Object>> createTenant(@RequestBody CreateTenantRequest req,
                                                         @AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        return ApiResponse.ok(platformAdminService.createTenant(
                req.tenantKey(), req.name(), req.ownerUsername(), req.ownerPassword()));
    }

    @PatchMapping("/tenants/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id, @RequestBody StatusRequest req,
                                          @AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        platformAdminService.updateStatus(id, req.status());
        return ApiResponse.ok();
    }

    public record CreateTenantRequest(@NotBlank String tenantKey, @NotBlank String name,
                                      @NotBlank String ownerUsername, @NotBlank String ownerPassword) {
    }

    public record StatusRequest(@NotBlank String status) {
    }
}
