package com.platform.capability.controller;

import com.platform.capability.service.PromptTemplateService;
import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Prompt 模板接口（需求 3.16 / P1）。
 */
@RestController
@RequestMapping("/api/v1/prompt-templates")
@RequiredArgsConstructor
public class PromptTemplateController {

    private final PromptTemplateService service;
    private final com.platform.agent.service.PromptOptimizeService promptOptimizeService;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String category) {
        return ApiResponse.ok(service.list(keyword, category));
    }

    public record OptimizeRequest(String prompt, String name, String description,
                                  String modelName, String instruction, String style) {
    }

    /** Prompt 模板 AI 优化：结果只返回给前端，需手动应用/保存。 */
    @PostMapping("/optimize")
    @PreAuthorize("hasAuthority('prompt:manage')")
    public ApiResponse<Map<String, Object>> optimize(@RequestBody OptimizeRequest req,
                                                     @AuthenticationPrincipal JwtPrincipal principal) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("prompt", req.prompt());
        body.put("name", req.name());
        body.put("description", req.description());
        body.put("modelName", req.modelName());
        body.put("instruction", req.instruction());
        body.put("style", req.style());
        return ApiResponse.ok(promptOptimizeService.optimize(body, principal));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('prompt:manage')")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(service.create(req, principal));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('prompt:manage')")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody Map<String, Object> req,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        service.update(id, req, principal);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('prompt:manage')")
    public ApiResponse<Void> delete(@PathVariable Long id, @AuthenticationPrincipal JwtPrincipal principal) {
        service.delete(id, principal);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/references")
    public ApiResponse<Map<String, Object>> references(@PathVariable Long id) {
        return ApiResponse.ok(service.references(id));
    }
}
