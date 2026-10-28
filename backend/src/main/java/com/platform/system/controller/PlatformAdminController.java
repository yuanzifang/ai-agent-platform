package com.platform.system.controller;

import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import com.platform.observability.service.AuditArchiveService;
import com.platform.observability.service.MessageArchiveService;
import com.platform.system.service.AlertWebhookService;
import com.platform.system.service.AuditService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 平台管理接口（需求 7.2）：仅平台管理员。
 */
@RestController
@RequestMapping("/api/v1/platform")
@RequiredArgsConstructor

@io.swagger.v3.oas.annotations.tags.Tag(name = "平台管理")
public class PlatformAdminController {

    private final PlatformAdminService platformAdminService;
    private final MessageArchiveService messageArchiveService;
    private final AuditArchiveService auditArchiveService;
    private final AuditService auditService;
    private final AlertWebhookService alertWebhookService;

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

    @GetMapping("/tenants/{id}/quota")
    public ApiResponse<com.platform.system.entity.SysTenantQuota> quota(@PathVariable Long id,
                                                                        @AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        return ApiResponse.ok(platformAdminService.quotaOf(id));
    }

    @PutMapping("/tenants/{id}/quota")
    public ApiResponse<Void> updateQuota(@PathVariable Long id,
                                         @RequestBody com.platform.system.entity.SysTenantQuota req,
                                         @AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        platformAdminService.updateQuota(id, req);
        return ApiResponse.ok();
    }

    @PatchMapping("/tenants/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id, @RequestBody StatusRequest req,
                                          @AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        platformAdminService.updateStatus(id, req.status(), principal.userId());
        return ApiResponse.ok();
    }

    /** 手动触发一条运维告警，验证全部已启用通道（webhook=钉钉/企微/飞书、邮件、短信）。 */
    @PostMapping("/alerts/test")
    public ApiResponse<Map<String, Object>> testAlert(@AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        Map<String, Boolean> channels = alertWebhookService.alertAll("测试告警",
                "平台管理员手动触发告警通道连通性测试");
        boolean sent = channels.containsValue(true);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("configured", alertWebhookService.isConfigured());
        result.put("sent", sent);
        result.put("channels", channels);
        result.put("status", alertWebhookService.channelStatus());
        auditService.record(principal.userId(), "alert.test", "alert", "multi-channel",
                sent ? "SUCCESS" : "FAILED", result);
        return ApiResponse.ok(result);
    }

    /** 手动执行消息过期归档（平台管理员；日常由 MessageArchiveService 定时任务执行）。 */
    @PostMapping("/maintenance/message-archive")
    public ApiResponse<Map<String, Object>> archiveMessages(@AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        Map<String, Object> result = messageArchiveService.archiveExpired();
        auditService.record(principal.userId(), "message.archive", "message_archive", "expired",
                Boolean.TRUE.equals(result.get("skipped")) ? "SKIPPED" : "SUCCESS", result);
        return ApiResponse.ok(result);
    }

    /** 手动执行审计过期归档（平台管理员；日常由 AuditArchiveService 定时任务执行）。 */
    @PostMapping("/maintenance/audit-archive")
    public ApiResponse<Map<String, Object>> archiveAudits(@AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        Map<String, Object> result = auditArchiveService.archiveExpired();
        auditService.record(principal.userId(), "audit.archive", "audit_operation", "expired",
                Boolean.TRUE.equals(result.get("skipped")) ? "SKIPPED" : "SUCCESS", result);
        return ApiResponse.ok(result);
    }

    public record CreateTenantRequest(@NotBlank String tenantKey, @NotBlank String name,
                                      @NotBlank String ownerUsername, @NotBlank String ownerPassword) {
    }

    public record StatusRequest(@NotBlank String status) {
    }
}
