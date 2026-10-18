package com.platform.capability.controller;

import com.platform.capability.service.AgentTemplateService;
import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 模板库接口（需求 3.24 / P1）。
 */
@RestController
@RequestMapping("/api/v1/agent-templates")
@RequiredArgsConstructor
public class AgentTemplateController {

    private final AgentTemplateService service;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String category) {
        return ApiResponse.ok(service.list(keyword, category));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    /** 一键从模板创建 Agent（归属当前用户，工具/Skill 绑定按 key 解析）。 */
    @PostMapping("/{id}/use")
    @PreAuthorize("hasAuthority('agent:create')")
    public ApiResponse<Map<String, Object>> use(@PathVariable Long id,
                                                @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(service.use(id, principal));
    }
}
