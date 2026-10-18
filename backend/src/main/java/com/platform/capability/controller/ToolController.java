package com.platform.capability.controller;

import com.platform.capability.entity.ToolVersion;
import com.platform.capability.service.ToolService;
import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Tool 管理接口（需求 3.14 / P1）：列表/详情/创建/编辑/删除/引用/测试/版本/发布/回滚。
 */
@RestController
@RequestMapping("/api/v1/tools")
@RequiredArgsConstructor
public class ToolController {

    private final ToolService toolService;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String category,
                                                 @RequestParam(required = false) String type) {
        return ApiResponse.ok(toolService.list(keyword, category, type));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(toolService.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('tool:manage')")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(toolService.create(req, principal));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('tool:manage')")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody Map<String, Object> req,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        toolService.update(id, req, principal);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('tool:manage')")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(toolService.delete(id, principal));
    }

    @GetMapping("/{id}/references")
    public ApiResponse<Map<String, Object>> references(@PathVariable Long id) {
        return ApiResponse.ok(toolService.references(id));
    }

    public record TestRequest(String argsJson) {
    }

    /** 工具测试：同一次执行展示输出与耗时（需求 3.14(2)）。 */
    @PostMapping("/{id}/test")
    @PreAuthorize("hasAuthority('tool:manage')")
    public ApiResponse<Map<String, Object>> test(@PathVariable Long id,
                                                 @RequestBody(required = false) TestRequest req) {
        return ApiResponse.ok(toolService.test(id, req == null ? null : req.argsJson()));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('tool:manage')")
    public ApiResponse<Map<String, Object>> publish(@PathVariable Long id,
                                                    @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(toolService.publish(id, principal));
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<List<ToolVersion>> versions(@PathVariable Long id) {
        return ApiResponse.ok(toolService.versions(id));
    }

    @PostMapping("/{id}/versions/{version}/rollback")
    @PreAuthorize("hasAuthority('tool:manage')")
    public ApiResponse<Map<String, Object>> rollback(@PathVariable Long id, @PathVariable String version,
                                                     @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(toolService.rollback(id, version, principal));
    }
}
