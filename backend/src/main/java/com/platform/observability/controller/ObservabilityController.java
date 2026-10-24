package com.platform.observability.controller;

import com.platform.common.response.ApiResponse;
import com.platform.common.util.TimeFormat;
import com.platform.observability.service.ObservabilityService;
import com.platform.observability.service.TraceRetentionService;
import com.platform.security.JwtPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 可观测接口（需求 7.3）：概览（指标卡/图）、Trace 列表（高级筛选）、Trace 详情（Span 树）、Sessions/Users/Versions。
 * 数组筛选参数用逗号分隔传递（tags、userIds、sessionIds）。
 */
@RestController
@RequestMapping("/api/v1/observability")
@RequiredArgsConstructor

@io.swagger.v3.oas.annotations.tags.Tag(name = "可观测")
public class ObservabilityController {

    private final ObservabilityService observabilityService;
    private final TraceRetentionService traceRetentionService;
    private final com.platform.observability.log.LogQueryService logQueryService;

    @PostMapping("/retention/run")
    public ApiResponse<Map<String, Object>> runRetention(
            @org.springframework.security.core.annotation.AuthenticationPrincipal JwtPrincipal principal) {
        if (principal == null || !principal.platformAdmin()) {
            throw new com.platform.common.exception.BusinessException(403, "仅平台管理员可手动执行保留清理");
        }
        return ApiResponse.ok(traceRetentionService.cleanupExpired());
    }

    @GetMapping("/logs")
    public ApiResponse<Map<String, Object>> logs(@RequestParam(required = false) String level,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "50") long pageSize) {
        return ApiResponse.ok(logQueryService.query(level, keyword, page, pageSize));
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview(@RequestParam(required = false) Long agentId,
                                                     @RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(observabilityService.overview(agentId, days));
    }

    @GetMapping("/traces")
    public ApiResponse<Map<String, Object>> traces(
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String tags,
            @RequestParam(required = false) String userIds,
            @RequestParam(required = false) String sessionIds,
            @RequestParam(required = false) Long tokenMin,
            @RequestParam(required = false) Long tokenMax,
            @RequestParam(required = false) Long latencyMin,
            @RequestParam(required = false) Long latencyMax,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean hideEval,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(observabilityService.traces(new ObservabilityService.AgentTraceQuery(
                agentId, status, kind, version, splitList(tags), splitLongs(userIds), splitList(sessionIds),
                tokenMin, tokenMax, latencyMin, latencyMax,
                TimeFormat.parseLenient(startTime, false), TimeFormat.parseLenient(endTime, true),
                keyword, hideEval, page, pageSize)));
    }

    @GetMapping("/traces/{traceId}")
    public ApiResponse<Map<String, Object>> traceDetail(@PathVariable String traceId) {
        return ApiResponse.ok(observabilityService.traceDetail(traceId));
    }

    @GetMapping("/sessions")
    public ApiResponse<Map<String, Object>> sessions(@RequestParam(required = false) Long agentId,
                                                     @RequestParam(required = false) String startTime,
                                                     @RequestParam(required = false) String endTime,
                                                     @RequestParam(required = false) String keyword,
                                                     @RequestParam(defaultValue = "1") long page,
                                                     @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(observabilityService.sessions(agentId,
                TimeFormat.parseLenient(startTime, false), TimeFormat.parseLenient(endTime, true),
                keyword, page, pageSize));
    }

    @GetMapping("/users")
    public ApiResponse<Map<String, Object>> users(@RequestParam(required = false) Long agentId,
                                                  @RequestParam(required = false) String startTime,
                                                  @RequestParam(required = false) String endTime,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(observabilityService.users(agentId,
                TimeFormat.parseLenient(startTime, false), TimeFormat.parseLenient(endTime, true),
                keyword, page, pageSize));
    }

    @GetMapping("/versions")
    public ApiResponse<Map<String, Object>> versions(@RequestParam(required = false) Long agentId,
                                                     @RequestParam(required = false) String startTime,
                                                     @RequestParam(required = false) String endTime,
                                                     @RequestParam(required = false) String keyword,
                                                     @RequestParam(defaultValue = "1") long page,
                                                     @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(observabilityService.versions(agentId,
                TimeFormat.parseLenient(startTime, false), TimeFormat.parseLenient(endTime, true),
                keyword, page, pageSize));
    }

    private static List<String> splitList(String joined) {
        if (joined == null || joined.isBlank()) return null;
        return Arrays.stream(joined.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static List<Long> splitLongs(String joined) {
        List<String> parts = splitList(joined);
        if (parts == null) return null;
        return parts.stream().map(Long::parseLong).toList();
    }
}
