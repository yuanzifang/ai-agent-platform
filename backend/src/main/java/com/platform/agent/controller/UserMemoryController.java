package com.platform.agent.controller;

import com.platform.agent.service.UserMemoryService;
import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 长期记忆：用户可见、可编辑的事实列表（需求 3.29 P2）。 */
@RestController
@RequestMapping("/api/v1/memories")
@RequiredArgsConstructor
public class UserMemoryController {

    private final UserMemoryService service;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) Long agentId,
                                                 @RequestParam(required = false) String keyword,
                                                 @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(service.list(principal, agentId, keyword));
    }

    @GetMapping("/profile")
    public ApiResponse<Map<String, Object>> profile(@AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(service.profile(principal));
    }

    @PutMapping("/profile")
    public ApiResponse<Map<String, Object>> replaceProfile(@RequestBody Map<String, Object> req,
                                                           @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(service.replaceProfile(req, principal));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(service.create(req, principal));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @RequestBody Map<String, Object> req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(service.update(id, req, principal));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        service.delete(id, principal);
        return ApiResponse.ok();
    }
}
