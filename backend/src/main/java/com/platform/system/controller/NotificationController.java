package com.platform.system.controller;

import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 站内通知接口。 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long pageSize,
                                                 @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(notificationService.list(principal, unreadOnly, page, pageSize));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Object>> unread(@AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(Map.of("count", notificationService.unreadCount(principal)));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Void> read(@PathVariable Long id, @AuthenticationPrincipal JwtPrincipal principal) {
        notificationService.markRead(id, principal);
        return ApiResponse.ok();
    }

    @PostMapping("/read-all")
    public ApiResponse<Void> readAll(@AuthenticationPrincipal JwtPrincipal principal) {
        notificationService.markAllRead(principal);
        return ApiResponse.ok();
    }
}
