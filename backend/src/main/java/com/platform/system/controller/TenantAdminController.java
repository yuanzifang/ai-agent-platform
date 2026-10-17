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
public class TenantAdminController {

    private final TenantAdminService tenantAdminService;

    @GetMapping
    public ApiResponse<Map<String, Object>> detail(@AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(tenantAdminService.detail(principal));
    }

    @PutMapping
    public ApiResponse<Void> update(@RequestBody Map<String, Object> req,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        tenantAdminService.update(principal, req);
        return ApiResponse.ok();
    }
}
