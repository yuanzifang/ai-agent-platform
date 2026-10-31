package com.platform.deploy.controller;

import com.platform.agent.controller.OpenApiController;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.entity.IntegrationApiKey;
import com.platform.common.exception.BusinessException;
import com.platform.deploy.service.AgentRuntimeGatewayService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台侧 Agent 运行时入口。nginx 只做 /v1 -> 平台的透明反代：
 * - Bearer 部署令牌 / 调用方 Key 在这里校验；
 * - 分钟限流、日配额、用量统计在这里执行；
 * - 对话仍复用 OpenApiController 的平台链路（审核/记忆/RAG/工具/计费/Trace）。
 */
@RestController
@RequestMapping("/api/v1/gateway/agent-runtime/{agentId}")
@RequiredArgsConstructor
@io.swagger.v3.oas.annotations.tags.Tag(name = "Agent 运行时网关",
        description = "平台侧 OpenAI 兼容运行时；nginx 只做入口和反向代理")
public class AgentRuntimeGatewayController {

    private static final long MAX_BODY = 2 * 1024 * 1024;

    private final AgentRuntimeGatewayService runtimeGatewayService;
    private final OpenApiController openApiController;

    @GetMapping("/v1/models")
    public Map<String, Object> models(
            @PathVariable Long agentId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        AgentRuntimeGatewayService.Caller caller =
                runtimeGatewayService.authenticate(agentId, authorization, true);
        runtimeGatewayService.agent(agentId);
        runtimeGatewayService.beforeRequest(agentId, caller, false);
        return Map.of(
                "object", "list",
                "data", List.of(Map.of(
                        "id", "agent-" + agentId,
                        "object", "model",
                        "created", System.currentTimeMillis() / 1000,
                        "owned_by", "ai-agent-platform")));
    }

    @PostMapping(value = "/v1/chat/completions",
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public Object chatCompletions(
            @PathVariable Long agentId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Gray-Tag", required = false) String grayTag,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (request.getContentLengthLong() > MAX_BODY) {
            throw new BusinessException(413, "请求体不能超过 2MB");
        }
        AgentRuntimeGatewayService.Caller caller =
                runtimeGatewayService.authenticate(agentId, authorization, true);
        boolean stream = Boolean.TRUE.equals(body.get("stream"))
                || "true".equals(String.valueOf(body.get("stream")))
                || String.valueOf(request.getHeader("Accept")).contains(MediaType.TEXT_EVENT_STREAM_VALUE);
        AgentRegistry agent = runtimeGatewayService.agent(agentId);
        IntegrationApiKey key = runtimeGatewayService.integrationKey(agentId);
        runtimeGatewayService.beforeRequest(agentId, caller, stream);
        return openApiController.completeFor(key, agent, body, grayTag, response);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(
            @PathVariable Long agentId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        AgentRuntimeGatewayService.Caller caller =
                runtimeGatewayService.authenticate(agentId, authorization, false);
        if (!"default".equals(caller.id())) {
            throw new BusinessException(403, "用量统计仅允许部署令牌访问");
        }
        return runtimeGatewayService.stats(agentId);
    }

    /** 保持 OpenAI 兼容错误结构；平台内部仍是统一 BusinessException。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> runtimeError(BusinessException e) {
        int status = e.getCode() >= 400 && e.getCode() < 600 ? e.getCode() : 400;
        String type = switch (status) {
            case 401, 403 -> "authentication_error";
            case 409 -> "conflict_error";
            case 413 -> "invalid_request_error";
            case 429 -> "rate_limit_error";
            default -> "invalid_request_error";
        };
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", e.getMessage());
        error.put("type", type);
        return ResponseEntity.status(status).body(Map.of("error", error));
    }
}
