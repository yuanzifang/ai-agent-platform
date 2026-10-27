package com.platform.system.controller;

import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import com.platform.system.entity.ResourceProfile;
import com.platform.system.service.PlatformAdminService;
import com.platform.system.service.ResourceProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 资源 Profile：平台管理员维护，普通用户只读已启用项。 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ResourceProfileController {

    private final ResourceProfileService profileService;
    private final PlatformAdminService platformAdminService;

    @GetMapping("/resource-profiles")
    public ApiResponse<List<ResourceProfile>> visible() {
        return ApiResponse.ok(profileService.list(true));
    }

    @GetMapping("/platform/resource-profiles")
    public ApiResponse<List<ResourceProfile>> list(@AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        return ApiResponse.ok(profileService.list(false));
    }

    @PostMapping("/platform/resource-profiles")
    public ApiResponse<ResourceProfile> create(@RequestBody Map<String, Object> req,
                                               @AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        return ApiResponse.ok(profileService.create(req));
    }

    @PutMapping("/platform/resource-profiles/{id}")
    public ApiResponse<ResourceProfile> update(@PathVariable Long id,
                                               @RequestBody Map<String, Object> req,
                                               @AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        return ApiResponse.ok(profileService.update(id, req));
    }

    @DeleteMapping("/platform/resource-profiles/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        profileService.delete(id);
        return ApiResponse.ok();
    }
}
