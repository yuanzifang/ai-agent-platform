package com.platform.system.controller;

import com.platform.common.response.ApiResponse;
import com.platform.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 审计查询（需求 3.17/7.2）：操作日志（哈希链留痕）+ 登录日志。
 */
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor

@io.swagger.v3.oas.annotations.tags.Tag(name = "审计")
public class AuditController {

    private final AuditService auditService;

    @GetMapping("/operations")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('audit:read')")
    public ApiResponse<Map<String, Object>> operations(@RequestParam(defaultValue = "1") long page,
                                                       @RequestParam(defaultValue = "20") long pageSize,
                                                       @RequestParam(required = false) String opType) {
        return ApiResponse.ok(auditService.operations(page, pageSize, opType));
    }

    @GetMapping("/verify")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('audit:read')")
    public ApiResponse<Map<String, Object>> verify() {
        return ApiResponse.ok(auditService.verifyChain());
    }

    @GetMapping("/logins")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('audit:read')")
    public ApiResponse<Map<String, Object>> logins(@RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(auditService.logins(page, pageSize));
    }
}
