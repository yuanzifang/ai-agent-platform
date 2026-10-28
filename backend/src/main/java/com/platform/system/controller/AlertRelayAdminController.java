package com.platform.system.controller;

import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.AlertRelayClient;
import com.platform.system.service.PlatformAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 平台管理员：查看 alert-relay 待升级告警并 ACK。 */
@RestController
@RequestMapping("/api/v1/platform/alert-relay")
@RequiredArgsConstructor
public class AlertRelayAdminController {

    private final AlertRelayClient relayClient;
    private final PlatformAdminService platformAdminService;

    @GetMapping("/state")
    public ApiResponse<Map<String, Object>> state(@AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        return ApiResponse.ok(relayClient.state());
    }

    @PostMapping("/ack")
    public ApiResponse<Map<String, Object>> ack(@RequestBody @Valid AckRequest req,
                                                 @AuthenticationPrincipal JwtPrincipal principal) {
        platformAdminService.requirePlatformAdmin(principal);
        return ApiResponse.ok(relayClient.ack(req.fingerprint()));
    }

    public record AckRequest(@NotBlank String fingerprint) {
    }
}
