package com.platform.observability.controller;

import com.platform.common.response.ApiResponse;
import com.platform.observability.service.GrayCompareService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 灰度 A/B 对比与轨迹分析（需求：模型切换后按灰度标记对比）。
 * 租户边界取 TenantContext；标记命中组（gray）vs 未命中组（baseline）的
 * 请求/错误率/延迟 P95/token/成本 + 按模型与 kind 的轨迹分析 + 按评测任务标记的得分对比。
 */
@RestController
@RequestMapping("/api/v1/observability/gray")
@RequiredArgsConstructor
@io.swagger.v3.oas.annotations.tags.Tag(name = "灰度对比")
public class GrayCompareController {

    private final GrayCompareService grayCompareService;

    @GetMapping("/compare")
    public ApiResponse<java.util.Map<String, Object>> compare(
            @RequestParam String tag,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) Long agentId) {
        return ApiResponse.ok(grayCompareService.compare(tag, days, agentId));
    }
}
