package com.platform.capability.controller;

import com.platform.capability.entity.SkillVersion;
import com.platform.capability.service.SkillService;
import com.platform.common.response.ApiResponse;
import com.platform.knowledge.service.KnowledgePermissionService;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Skill 管理接口（需求 3.13 / P1）：列表/详情/创建/编辑/删除/引用/版本/发布/回滚/测试。
 */
@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String category,
                                                 @RequestParam(required = false) String type) {
        return ApiResponse.ok(skillService.list(keyword, category, type));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(skillService.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('skill:manage')")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(skillService.create(req, principal));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('skill:manage')")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody Map<String, Object> req,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        skillService.update(id, req, principal);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('skill:manage')")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(skillService.delete(id, principal));
    }

    @GetMapping("/{id}/references")
    public ApiResponse<Map<String, Object>> references(@PathVariable Long id) {
        return ApiResponse.ok(skillService.references(id));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('skill:manage')")
    public ApiResponse<Map<String, Object>> publish(@PathVariable Long id,
                                                    @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(skillService.publish(id, principal));
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<List<SkillVersion>> versions(@PathVariable Long id) {
        return ApiResponse.ok(skillService.versions(id));
    }

    @PostMapping("/{id}/versions/{version}/rollback")
    @PreAuthorize("hasAuthority('skill:manage')")
    public ApiResponse<Map<String, Object>> rollback(@PathVariable Long id, @PathVariable String version,
                                                     @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(skillService.rollback(id, version, principal));
    }

    public record TestRequest(String input) {
    }

    /** 脚本直跑测试（支持未保存代码）；只执行脚本，不调用模型。 */
    @PostMapping("/script/test")
    @PreAuthorize("hasAuthority('skill:manage')")
    public ApiResponse<Map<String, Object>> testScript(@RequestBody Map<String, Object> req) {
        return ApiResponse.ok(skillService.testScript(req));
    }

    /** 技能测试：当前配置直接试跑，不写正式 Trace（需求 3.13(6)）。 */
    @PostMapping("/{id}/test")
    @PreAuthorize("hasAuthority('skill:manage')")
    public ApiResponse<Map<String, Object>> test(@PathVariable Long id, @RequestBody(required = false) TestRequest req,
                                                 @AuthenticationPrincipal JwtPrincipal principal) {
        var viewer = KnowledgePermissionService.Viewer.of(principal.userId(), principal.roles());
        return ApiResponse.ok(skillService.test(id, req == null ? null : req.input(), viewer, principal));
    }
}
