package com.platform.system.controller;

import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.UserAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 成员与邀请接口（需求 7.2）。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserAdminService userAdminService;

    @GetMapping("/users")
    public ApiResponse<List<Map<String, Object>>> members(@AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(userAdminService.members(principal));
    }

    @PutMapping("/users/{id}/roles")
    public ApiResponse<Void> assignRoles(@PathVariable Long id, @RequestBody RolesRequest req,
                                         @AuthenticationPrincipal JwtPrincipal principal) {
        userAdminService.assignRoles(id, req.roles(), principal);
        return ApiResponse.ok();
    }

    @PostMapping("/auth/invitations")
    public ApiResponse<Map<String, Object>> invite(@Valid @RequestBody InviteRequest req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(userAdminService.invite(req.email(), req.role(), principal));
    }

    @GetMapping("/auth/invitations")
    public ApiResponse<List<Map<String, Object>>> invitations(@AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(userAdminService.invitations(principal));
    }

    /** 免登录激活（SecurityConfig 已放行 /auth/invitations/accept）。 */
    @PostMapping("/auth/invitations/accept")
    public ApiResponse<Map<String, Object>> accept(@Valid @RequestBody AcceptRequest req) {
        return ApiResponse.ok(userAdminService.accept(req.token(), req.username(), req.password()));
    }

    public record RolesRequest(@NotNull List<String> roles) {
    }

    public record InviteRequest(String email, @NotBlank String role) {
    }

    public record AcceptRequest(@NotBlank String token, @NotBlank String username, @NotBlank String password) {
    }
}
