package com.platform.agent.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.entity.AgentVersion;
import com.platform.agent.service.AgentService;
import com.platform.agent.service.ChatService;
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
public class AgentController {

    private final AgentService agentService;
    private final ChatService chatService;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long pageSize) {
        Page<AgentRegistry> result = agentService.list(keyword, status, page, pageSize);
        return ApiResponse.ok(Map.of(
                "list", result.getRecords().stream().map(this::summary).toList(),
                "total", result.getTotal(), "page", page, "pageSize", pageSize));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> req,
                                                   @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(agentService.create(req, principal));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long id) {
        return ApiResponse.ok(agentService.detail(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @RequestBody Map<String, Object> req,
                                                   @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return ApiResponse.ok(agentService.update(id, req, ifMatch));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @AuthenticationPrincipal JwtPrincipal principal) {
        agentService.delete(id, principal);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<Map<String, Object>> publish(@PathVariable Long id,
                                                    @AuthenticationPrincipal JwtPrincipal principal) {
        return ApiResponse.ok(agentService.publish(id, principal));
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

    /** 调试对话：用未保存配置执行，不写会话/正式 Trace（需求文档 3.1.3(10)）。 */
    @PostMapping("/debug/chat")
    public ApiResponse<Map<String, Object>> debugChat(@RequestBody DebugChatRequest req) {
        return ApiResponse.ok(chatService.debugChat(req.config(), req.message()));
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
