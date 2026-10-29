package com.platform.observability.controller;

import com.platform.common.response.ApiResponse;
import com.platform.observability.service.PartitionMaintenanceService;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.PlatformAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 大表分区审计与手动补齐（平台管理员）。 */
@RestController
@RequestMapping("/api/v1/platform/partitions")
@RequiredArgsConstructor
public class PartitionController {

    private final PartitionMaintenanceService partitionService;
    private final PlatformAdminService platformAdminService;

    @GetMapping
    public ApiResponse<Map<String, Object>> status(@AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        return ApiResponse.ok(partitionService.status());
    }

    @PostMapping("/maintain")
    public ApiResponse<Map<String, Object>> maintain(@AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        int created = partitionService.maintain();
        return ApiResponse.ok(Map.of("created", created, "status", partitionService.status()));
    }
}
