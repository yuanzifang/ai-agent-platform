package com.platform.model.controller;

import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.common.response.ApiResponse;
import com.platform.model.mapper.ModelMapper;
import com.platform.model.entity.ModelEntity;
import com.platform.model.entity.Provider;
import com.platform.model.mapper.ProviderMapper;
import com.platform.model.mapper.ProviderCredentialMapper;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.system.service.AuditService;
import com.platform.model.service.ProviderService;
import com.platform.model.service.ChatModelRouter;
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

@io.swagger.v3.oas.annotations.tags.Tag(name = "模型接入")
public class ProviderController {

    private final ProviderMapper providerMapper;
    private final ModelMapper modelMapper;
    private final ProviderService providerService;
    private final ChatModelRouter modelRouter;
    private final ProviderCredentialMapper credentialMapper;
    private final AgentRegistryMapper agentRegistryMapper;
    private final AuditService auditService;

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
            m.put("credentialConfigured", providerService.hasCredential(p.getId()));
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
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            providerService.saveCredential(p.getId(), req.apiKey());
        }
        return ApiResponse.ok(Map.of("id", p.getId()));
    }

    public record CreateProviderRequest(@NotBlank String name, @NotBlank String providerType,
                                        @NotBlank String baseUrl, String proxy,
                                        Integer timeoutMs, Integer retryTimes, String apiKey) {
    }

    public record CredentialRequest(String apiKey) {
    }

    /** 设置/清除租户 Provider 的 API Key（加密存储，不回显）。 */
    @PutMapping("/provider/{id}/credential")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('model:manage')")
    public ApiResponse<Void> saveCredential(@PathVariable Long id, @RequestBody CredentialRequest req) {
        providerService.saveCredential(id, req.apiKey());
        return ApiResponse.ok();
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
        if (req.cacheReadPrice() != null) m.setCacheReadPrice(req.cacheReadPrice());
        if (req.cacheWritePrice() != null) m.setCacheWritePrice(req.cacheWritePrice());
        if (req.status() != null && List.of("ENABLED", "DISABLED").contains(req.status())) {
            m.setStatus(req.status());
        }
        if (req.displayName() != null && !req.displayName().isBlank()) m.setDisplayName(req.displayName());
        if (req.contextWindow() != null && req.contextWindow() >= 0) m.setContextWindow(req.contextWindow());
        // 思考能力（模型级）：是否支持 / 参数风格 / 可选级别 / 默认级别
        if (req.reasoning() != null || req.reasoningParam() != null
                || req.reasoningLevels() != null || req.defaultReasoningEffort() != null) {
            Map<String, Object> caps = readJson(m.getCapabilities());
            if (req.reasoning() != null) caps.put("reasoning", req.reasoning());
            if (req.reasoningParam() != null) {
                String style = req.reasoningParam().trim().toLowerCase(java.util.Locale.ROOT);
                if (!List.of("effort", "budget").contains(style)) {
                    throw new BusinessException(400, "思考参数仅支持 effort / budget");
                }
                caps.put("reasoningParam", style);
            }
            if (req.reasoningLevels() != null) {
                List<String> levels = req.reasoningLevels().stream()
                        .map(ProviderController::normalizeLevel).distinct().toList();
                caps.put("reasoningLevels", levels);
            }
            m.setCapabilities(writeJson(caps));

            if (req.defaultReasoningEffort() != null) {
                Map<String, Object> defaults = readJson(m.getDefaultParams());
                defaults.put("reasoningEffort", normalizeLevel(req.defaultReasoningEffort()));
                m.setDefaultParams(writeJson(defaults));
            }
        }
        modelMapper.updateIgnoreTenant(m);
        return ApiResponse.ok(Map.of("id", id));
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(String raw) {
        if (raw == null || raw.isBlank()) return new java.util.LinkedHashMap<>();
        try {
            return new java.util.LinkedHashMap<>(JSON.readValue(raw, Map.class));
        } catch (Exception e) {
            return new java.util.LinkedHashMap<>();
        }
    }

    private static String writeJson(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(500, "模型能力配置序列化失败");
        }
    }

    /** 思考级别统一为 minimal/low/medium/high；非法值按 medium 处理。 */
    private static String normalizeLevel(String level) {
        String v = level == null ? "" : level.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (v) {
            case "minimal", "low", "medium", "high" -> v;
            case "max", "xhigh" -> "high";
            case "mid", "normal" -> "medium";
            default -> "medium";
        };
    }

    public record UpdateModelRequest(java.math.BigDecimal inputPrice, java.math.BigDecimal outputPrice,
                                     java.math.BigDecimal cacheReadPrice, java.math.BigDecimal cacheWritePrice,
                                     String status, String displayName, Integer contextWindow,
                                     Boolean reasoning, String reasoningParam,
                                     List<String> reasoningLevels, String defaultReasoningEffort) {
    }

    public record CreateModelRequest(@NotBlank String modelName, String displayName, String purpose,
                                     Integer contextWindow,
                                     java.math.BigDecimal inputPrice, java.math.BigDecimal outputPrice,
                                     java.math.BigDecimal cacheReadPrice, java.math.BigDecimal cacheWritePrice) {
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
        if (!List.of("CHAT", "PLATFORM", "EMBEDDING", "RERANK", "VISION", "ASR", "OCR").contains(purpose)) {
            throw new BusinessException(400, "用途仅支持 CHAT/PLATFORM/EMBEDDING/RERANK/VISION/ASR/OCR");
        }
        m.setPurpose(purpose);
        m.setContextWindow(req.contextWindow() == null ? 0 : Math.max(req.contextWindow(), 0));
        // 接入时写清单价（元/千 Token）：输入/输出/缓存命中/缓存写入
        m.setInputPrice(req.inputPrice());
        m.setOutputPrice(req.outputPrice());
        m.setCacheReadPrice(req.cacheReadPrice());
        m.setCacheWritePrice(req.cacheWritePrice());
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

    /** 删除本租户自建模型；平台预置模型和仍被 Agent 引用的模型禁止删除。 */
    @DeleteMapping("/models/{id}")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('model:manage')")
    public ApiResponse<Void> deleteModel(@PathVariable Long id,
                                         @AuthenticationPrincipal JwtPrincipal principal) {
        ModelEntity model = modelMapper.findByIdIgnore(id);
        if (model == null) throw new BusinessException(404, "模型不存在");
        if (model.getTenantId() == null) throw new BusinessException(403, "平台预置模型不可删除");
        Long tenantId = principal.tenantId() != null ? principal.tenantId() : TenantContext.getTenantId();
        if (!model.getTenantId().equals(tenantId)) throw new BusinessException(403, "无权删除该模型");
        if (referencedByAgent(model.getModelName())) {
            throw new BusinessException(409, "模型仍被 Agent 引用，请先切换 Agent 模型");
        }
        modelMapper.deleteById(id);
        modelRouter.evictAll();
        auditService.record(principal.userId(), "model.delete", "model", String.valueOf(id),
                "SUCCESS", Map.of("modelName", model.getModelName()));
        return ApiResponse.ok();
    }

    /** 删除本租户自建 Provider；平台预置 Provider 或仍有模型的 Provider 禁止删除。 */
    @DeleteMapping("/provider/{id}")
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('model:manage')")
    public ApiResponse<Void> deleteProvider(@PathVariable Long id,
                                            @AuthenticationPrincipal JwtPrincipal principal) {
        Provider provider = providerMapper.findByIdIgnore(id);
        if (provider == null) throw new BusinessException(404, "Provider 不存在");
        if (provider.getTenantId() == null) throw new BusinessException(403, "平台预置 Provider 不可删除");
        Long tenantId = principal.tenantId() != null ? principal.tenantId() : TenantContext.getTenantId();
        if (!provider.getTenantId().equals(tenantId)) throw new BusinessException(403, "无权删除该 Provider");
        long modelCount = modelMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModelEntity>()
                .eq(ModelEntity::getProviderId, id));
        if (modelCount > 0) throw new BusinessException(409, "请先删除该 Provider 下的模型");
        credentialMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.platform.model.entity.ProviderCredential>()
                .eq(com.platform.model.entity.ProviderCredential::getProviderId, id));
        providerMapper.deleteById(id);
        modelRouter.evictAll();
        auditService.record(principal.userId(), "provider.delete", "provider", String.valueOf(id),
                "SUCCESS", Map.of("name", provider.getName()));
        return ApiResponse.ok();
    }

    private boolean referencedByAgent(String modelName) {
        if (modelName == null || modelName.isBlank()) return false;
        List<AgentRegistry> agents = agentRegistryMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentRegistry>()
                        .ne(AgentRegistry::getStatus, "DELETED"));
        for (AgentRegistry agent : agents) {
            Map<String, Object> config = readJson(agent.getModelConfig());
            if (modelName.equals(String.valueOf(config.get("modelName")))
                    || modelName.equals(String.valueOf(config.get("fallbackModelName")))) {
                return true;
            }
        }
        return false;
    }

    @PostMapping("/provider/{id}/models/sync")
    public ApiResponse<Map<String, Object>> sync(@PathVariable Long id) {
        return ApiResponse.ok(providerService.syncModels(id));
    }
}
