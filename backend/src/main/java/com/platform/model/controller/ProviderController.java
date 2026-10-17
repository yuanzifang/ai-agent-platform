package com.platform.model.controller;

import com.platform.common.context.TenantContext;
import com.platform.common.response.ApiResponse;
import com.platform.model.entity.ModelEntity;
import com.platform.model.entity.Provider;
import com.platform.model.mapper.ProviderMapper;
import com.platform.model.service.ProviderService;
import com.platform.security.JwtPrincipal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Provider / 模型接口（需求文档 7.2 / 7.3）。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ProviderController {

    private final ProviderMapper providerMapper;
    private final ProviderService providerService;

    public record ModelItem(Long modelId, Long providerId, String providerName, String providerType,
                            String modelName, String displayName, String purpose,
                            String capabilities, String defaultParams) {
    }

    /** 模型选择器数据源：平台预置 + 本租户自建（仅启用）。 */
    @GetMapping("/provider/models")
    public ApiResponse<List<ModelItem>> listModels(@AuthenticationPrincipal JwtPrincipal principal,
                                                   @RequestParam(required = false) String purpose) {
        Long tenantId = principal.tenantId() != null ? principal.tenantId() : TenantContext.getTenantId();
        List<Provider> providers = providerMapper.listVisible(tenantId);
        List<ModelEntity> models = providerMapper.listVisibleModels(tenantId);
        return ApiResponse.ok(models.stream()
                .filter(m -> purpose == null || purpose.isBlank() || purpose.equals(m.getPurpose()))
                .map(m -> {
                    Provider p = providers.stream()
                            .filter(x -> x.getId().equals(m.getProviderId())).findFirst().orElse(null);
                    return new ModelItem(m.getId(), m.getProviderId(),
                            p == null ? "" : p.getName(), p == null ? "" : p.getProviderType(),
                            m.getModelName(), m.getDisplayName(), m.getPurpose(),
                            m.getCapabilities(), m.getDefaultParams());
                }).toList());
    }

    @PostMapping("/provider")
    public ApiResponse<Map<String, Object>> createProvider(@RequestBody CreateProviderRequest req,
                                                           @AuthenticationPrincipal JwtPrincipal principal) {
        Provider p = new Provider();
        p.setName(req.name());
        p.setProviderType(req.providerType());
        p.setBaseUrl(req.baseUrl());
        p.setProxy(req.proxy());
        p.setTimeoutMs(req.timeoutMs() == null ? 60000 : req.timeoutMs());
        p.setRetryTimes(req.retryTimes() == null ? 2 : req.retryTimes());
        p.setStatus("ENABLED");
        p.setTenantId(principal.tenantId()); // 租户自建；平台预置走种子/平台管理员链路
        providerMapper.insert(p);
        return ApiResponse.ok(Map.of("id", p.getId()));
    }

    public record CreateProviderRequest(@NotBlank String name, @NotBlank String providerType,
                                        @NotBlank String baseUrl, String proxy,
                                        Integer timeoutMs, Integer retryTimes) {
    }

    @PostMapping("/provider/{id}/test")
    public ApiResponse<Map<String, Object>> test(@PathVariable Long id) {
        return ApiResponse.ok(providerService.test(id));
    }

    @PostMapping("/provider/{id}/models/sync")
    public ApiResponse<Map<String, Object>> sync(@PathVariable Long id) {
        return ApiResponse.ok(providerService.syncModels(id));
    }
}
