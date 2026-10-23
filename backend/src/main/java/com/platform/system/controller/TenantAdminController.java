package com.platform.system.controller;

import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.TenantAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 租户配置接口（需求 7.2）。
 */
@RestController
@RequestMapping("/api/v1/tenant")
@RequiredArgsConstructor

@io.swagger.v3.oas.annotations.tags.Tag(name = "租户设置")
public class TenantAdminController {

    private final TenantAdminService tenantAdminService;

    @GetMapping
    public ApiResponse<Map<String, Object>> detail(@AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(tenantAdminService.detail(principal));
    }

    public record TransferOwnerRequest(Long userId) {
    }

    /** 所有者转让（仅当前 OWNER/平台管理员）。 */
    @PutMapping("/owner")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('tenant:manage')")
    public ApiResponse<Void> transferOwner(@RequestBody TransferOwnerRequest req,
                                           @AuthenticationPrincipal JwtPrincipal principal) {
        tenantAdminService.transferOwner(req.userId(), principal);
        return ApiResponse.ok();
    }

    @PutMapping
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('tenant:manage')")
    public ApiResponse<Void> update(@RequestBody Map<String, Object> req,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        tenantAdminService.update(principal, req);
        return ApiResponse.ok();
    }
}
