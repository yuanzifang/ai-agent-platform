package com.platform.observability.controller;

import com.platform.common.response.ApiResponse;
import com.platform.observability.service.MetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 监控统计接口（需求 7.3）：GET /api/v1/metrics/agent/{id}。
 * period：24h（默认）/ 7d / 30d / custom（配 startTime、endTime）。
 */
@RestController
@RequestMapping("/api/v1/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final MetricsService metricsService;

    @GetMapping("/agent/{id}")
    public ApiResponse<Map<String, Object>> agentMetrics(@PathVariable Long id,
                                                         @RequestParam(defaultValue = "24h") String period,
                                                         @RequestParam(required = false) String startTime,
                                                         @RequestParam(required = false) String endTime) {
        return ApiResponse.ok(metricsService.agentMetrics(id, period, startTime, endTime));
    }
}
