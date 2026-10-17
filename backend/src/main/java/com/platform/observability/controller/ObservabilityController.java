package com.platform.observability.controller;

import com.platform.common.response.ApiResponse;
import com.platform.observability.service.ObservabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 可观测接口（需求 7.2）：概览、Trace 列表、Span 树。
 */
@RestController
@RequestMapping("/api/v1/observability")
@RequiredArgsConstructor
public class ObservabilityController {

    private final ObservabilityService observabilityService;

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview(@RequestParam(required = false) Long agentId,
                                                     @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(observabilityService.overview(agentId, days));
    }

    @GetMapping("/traces")
    public ApiResponse<Map<String, Object>> traces(@RequestParam(required = false) Long agentId,
                                                   @RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(observabilityService.traces(agentId, page, pageSize));
    }

    @GetMapping("/traces/{traceId}")
    public ApiResponse<Map<String, Object>> traceDetail(@PathVariable String traceId) {
        return ApiResponse.ok(observabilityService.traceDetail(traceId));
    }
}
