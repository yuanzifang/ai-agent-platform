package com.platform.model.controller;

import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.common.response.ApiResponse;
import com.platform.model.mapper.ModelMapper;
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
    private final ModelMapper modelMapper;
    private final ProviderService providerService;

    public record ModelItem(Long modelId, Long providerId, String providerName, String providerType,
                            String modelName, String displayName, String purpose,
                            String capabilities, String defaultParams,
                            java.math.BigDecimal inputPrice, java.math.BigDecimal outputPrice,
                            Integer contextWindow, String status) {
    }

    /** Provider 列表：平台预置（tenant_id 空）+ 本租户自建；不含凭证。 */
    @GetMapping("/provider")
    public ApiResponse<List<Map<String, Object>>> listProviders(@AuthenticationPrincipal JwtPrincipal principal) {
        Long tenantId = principal.tenantId() != null ? principal.tenantId() : TenantContext.getTenantId();
        List<Provider> providers = providerMapper.listVisible(tenantId);
        return ApiResponse.ok(providers.stream().map(p -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("providerType", p.getProviderType());
            m.put("baseUrl", p.getBaseUrl());
            m.put("status", p.getStatus());
            m.put("tenantId", p.getTenantId());
            m.put("preset", p.getTenantId() == null);
            return m;
        }).toList());
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
                            m.getCapabilities(), m.getDefaultParams(),
                            m.getInputPrice(), m.getOutputPrice(), m.getContextWindow(), m.getStatus());
                }).toList());
    }

    @PostMapping("/provider")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('model:manage')")
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

    /** 模型默认定价/启停（计价权威为 billing_rule，此处仅默认预填，需求 3.7）。 */
    @PutMapping("/models/{id}")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('model:manage')")
    public ApiResponse<Map<String, Object>> updateModel(@PathVariable Long id,
                                                        @RequestBody UpdateModelRequest req) {
        ModelEntity m = modelMapper.findByIdIgnore(id);
        if (m == null) throw new BusinessException(404, "模型不存在");
        if (req.inputPrice() != null) m.setInputPrice(req.inputPrice());
        if (req.outputPrice() != null) m.setOutputPrice(req.outputPrice());
        if (req.status() != null && List.of("ENABLED", "DISABLED").contains(req.status())) {
            m.setStatus(req.status());
        }
        if (req.displayName() != null && !req.displayName().isBlank()) m.setDisplayName(req.displayName());
        if (req.contextWindow() != null && req.contextWindow() >= 0) m.setContextWindow(req.contextWindow());
        modelMapper.updateIgnoreTenant(m);
        return ApiResponse.ok(Map.of("id", id));
    }

    public record UpdateModelRequest(java.math.BigDecimal inputPrice, java.math.BigDecimal outputPrice,
                                     String status, String displayName, Integer contextWindow) {
    }

    public record CreateModelRequest(@NotBlank String modelName, String displayName, String purpose,
                                     Integer contextWindow) {
    }

    /** 手动新增模型（用于未同步到的模型），创建时选择上下文窗口（tokens）。 */
    @PostMapping("/provider/{providerId}/models")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('model:manage')")
    public ApiResponse<Map<String, Object>> createModel(@PathVariable Long providerId,
                                                        @RequestBody CreateModelRequest req,
                                                        @AuthenticationPrincipal JwtPrincipal principal) {
        com.platform.model.entity.Provider provider = providerMapper.findByIdIgnore(providerId);
        if (provider == null) throw new BusinessException(404, "Provider 不存在");
        Long tenantId = principal.tenantId() != null ? principal.tenantId() : TenantContext.getTenantId();
        if (provider.getTenantId() != null && !provider.getTenantId().equals(tenantId)) {
            throw new BusinessException(403, "无权在该 Provider 下创建模型");
        }
        long exists = modelMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModelEntity>()
                .eq(ModelEntity::getProviderId, providerId)
                .eq(ModelEntity::getModelName, req.modelName().trim()));
        if (exists > 0) throw new BusinessException(400, "模型已存在：" + req.modelName());
        ModelEntity m = new ModelEntity();
        m.setProviderId(providerId);
        m.setModelName(req.modelName().trim());
        m.setDisplayName(req.displayName() == null || req.displayName().isBlank()
                ? req.modelName().trim() : req.displayName().trim());
        String purpose = req.purpose() == null ? "CHAT" : req.purpose().toUpperCase();
        if (!List.of("CHAT", "EMBEDDING", "RERANK").contains(purpose)) {
            throw new BusinessException(400, "用途仅支持 CHAT/EMBEDDING/RERANK");
        }
        m.setPurpose(purpose);
        m.setContextWindow(req.contextWindow() == null ? 0 : Math.max(req.contextWindow(), 0));
        m.setStatus("ENABLED");
        m.setTenantId(tenantId);
        m.setCreatedAt(java.time.LocalDateTime.now());
        modelMapper.insert(m);
        return ApiResponse.ok(Map.of("id", m.getId()));
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
