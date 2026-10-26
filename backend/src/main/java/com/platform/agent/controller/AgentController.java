package com.platform.agent.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.entity.AgentVersion;
import com.platform.agent.service.AgentService;
import com.platform.agent.service.ChatService;
import com.platform.common.exception.BusinessException;
import com.platform.common.response.ApiResponse;
import com.platform.security.JwtPrincipal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Agent 接口（需求文档 7.2 / 7.3 字段定义）。
 */
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor

@io.swagger.v3.oas.annotations.tags.Tag(name = "Agent 管理")
public class AgentController {

    private final AgentService agentService;
    private final com.platform.eval.service.EvalService evalService;
    private final ChatService chatService;
    private final com.platform.agent.service.PromptOptimizeService promptOptimizeService;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long pageSize,
                                                 @AuthenticationPrincipal JwtPrincipal principal) {
        Page<AgentRegistry> result = agentService.list(keyword, status, page, pageSize, principal);
        java.util.List<Long> ids = result.getRecords().stream().map(AgentRegistry::getId).toList();
        Map<Long, Map<String, Object>> flags = agentService.capabilityFlags(ids);
        return ApiResponse.ok(Map.of(
                "list", result.getRecords().stream().map(a -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>(summary(a));
                    m.put("capability", flags.get(a.getId()));
                    return m;
                }).toList(),
                "total", result.getTotal(), "page", page, "pageSize", pageSize));
    }

    @PostMapping
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('agent:create')")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(agentService.create(req, principal));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long id) {
        return ApiResponse.ok(agentService.detail(id));
    }

    @PutMapping("/{id}")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @RequestBody Map<String, Object> req,
                                                   @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return ApiResponse.ok(agentService.update(id, req, ifMatch));
    }

    @DeleteMapping("/{id}")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('agent:delete')")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        agentService.delete(id, principal);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/publish")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('agent:publish')")
    public ApiResponse<Map<String, Object>> publish(@PathVariable Long id,
                                                    @RequestBody(required = false) PublishRequest req,
                                                    @AuthenticationPrincipal JwtPrincipal principal) {
        boolean force = req != null && Boolean.TRUE.equals(req.force());
        Long datasetId = req == null ? null : req.gateDatasetId();
        if (force && !principal.roles().contains("OWNER") && !principal.roles().contains("ADMIN")) {
            throw new BusinessException(403, "强制发布需 OWNER/ADMIN 权限");
        }
        // 门禁在事务外执行（内含评测轮询等待，事务内睡眠会占死数据库连接）
        if (!force) {
            Map<String, Object> gate = evalService.preReleaseGate(id, datasetId, principal.userId());
            if (Boolean.FALSE.equals(gate.get("skipped")) && !Boolean.TRUE.equals(gate.get("passed"))) {
                throw new BusinessException(400, "预发布测评未通过，无法发布（通过率 "
                        + gate.get("passRate") + " < 阈值 " + gate.get("threshold")
                        + "；OWNER/ADMIN 可强制发布）");
            }
        }
        return ApiResponse.ok(agentService.publish(id, principal, datasetId, force));
    }

    public record PublishRequest(Long gateDatasetId, Boolean force) {
    }

    /** 发布失败后复用门禁缓存返回分步明细；不触发实际发布。 */
    @PostMapping("/{id}/gate-preview")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('agent:publish')")
    public ApiResponse<Map<String, Object>> gatePreview(@PathVariable Long id,
                                                        @RequestBody(required = false) PublishRequest req,
                                                        @AuthenticationPrincipal JwtPrincipal principal) {
        Long datasetId = req == null ? null : req.gateDatasetId();
        return ApiResponse.ok(evalService.preReleaseGate(id, datasetId, principal.userId()));
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<List<AgentVersion>> versions(@PathVariable Long id) {
        return ApiResponse.ok(agentService.versions(id));
    }

    @PostMapping("/{id}/versions/{version}/rollback")
    public ApiResponse<Map<String, Object>> rollback(@PathVariable Long id, @PathVariable String version,
                                                     @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(agentService.rollback(id, version, principal));
    }

    @GetMapping("/{id}/grants")
    public ApiResponse<List<Long>> grants(@PathVariable Long id) {
        return ApiResponse.ok(agentService.grants(id));
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('agent:edit')")
    @PutMapping("/{id}/grants")
    public ApiResponse<Void> replaceGrants(@PathVariable Long id, @RequestBody GrantsRequest req) {
        agentService.replaceGrants(id, req.userIds());
        return ApiResponse.ok();
    }

    public record GrantsRequest(List<Long> userIds) {
    }

    /** 调试对话：用未保存配置执行，不写会话/正式 Trace（需求文档 3.1.3(10)）。 */
    @PostMapping("/debug/chat")
    public ApiResponse<Map<String, Object>> debugChat(@RequestBody DebugChatRequest req,
                                                      @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(chatService.debugChat(req.config(), req.message(),
                com.platform.knowledge.service.KnowledgePermissionService.Viewer.of(
                        principal.userId(), principal.roles()), principal));
    }

    public record PromptOptimizeRequest(@NotBlank String prompt, String name, String description,
                                        String modelName, String instruction, String style) {
    }

    /** AI 提示词优化（Agent 编辑页）：只返回优化结果，不自动保存；计入智能生成用量。 */
    @PostMapping("/prompt/optimize")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('agent:edit')")
    public ApiResponse<Map<String, Object>> optimizePrompt(@RequestBody @jakarta.validation.Valid PromptOptimizeRequest req,
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

    public record DebugChatRequest(Map<String, Object> config, @NotBlank String message) {
    }

    private Map<String, Object> summary(AgentRegistry a) {
        return Map.of("id", a.getId(), "agentKey", a.getAgentKey(),
                "name", a.getName() == null ? "" : a.getName(),
                "description", a.getDescription() == null ? "" : a.getDescription(),
                "status", a.getStatus(), "visibility", a.getVisibility(),
                "updatedAt", String.valueOf(a.getUpdatedAt()));
    }
}
