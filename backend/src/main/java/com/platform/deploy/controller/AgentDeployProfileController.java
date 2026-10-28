package com.platform.deploy.controller;

import com.platform.common.response.ApiResponse;
import com.platform.deploy.entity.AgentDeployProfile;
import com.platform.deploy.service.AgentDeployProfileService;
import com.platform.deploy.service.K8sResourceMapper;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.PlatformAdminService;
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

/** 部署资源 Profile：平台管理员维护，Agent 部署时选择启用项。 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AgentDeployProfileController {

    private final AgentDeployProfileService profileService;
    private final K8sResourceMapper k8sResourceMapper;
    private final PlatformAdminService platformAdminService;

    @GetMapping("/deploy-resource-profiles")
    public ApiResponse<List<AgentDeployProfile>> visible() {
        return ApiResponse.ok(profileService.list(true));
    }

    @GetMapping("/platform/deploy-resource-profiles")
    public ApiResponse<List<AgentDeployProfile>> list(@AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        return ApiResponse.ok(profileService.list(false));
    }

    @GetMapping("/platform/deploy-resource-profiles/{id}/k8s-resources")
    public ApiResponse<Map<String, Object>> k8sResources(@PathVariable Long id,
                                                         @AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        return ApiResponse.ok(k8sResourceMapper.resources(profileService.get(id)));
    }

    @PostMapping("/platform/deploy-resource-profiles")
    public ApiResponse<AgentDeployProfile> create(@RequestBody Map<String, Object> req,
                                                  @AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        return ApiResponse.ok(profileService.create(req));
    }

    @PutMapping("/platform/deploy-resource-profiles/{id}")
    public ApiResponse<AgentDeployProfile> update(@PathVariable Long id,
                                                  @RequestBody Map<String, Object> req,
                                                  @AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        return ApiResponse.ok(profileService.update(id, req));
    }

    @DeleteMapping("/platform/deploy-resource-profiles/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        profileService.delete(id);
        return ApiResponse.ok();
    }
}
