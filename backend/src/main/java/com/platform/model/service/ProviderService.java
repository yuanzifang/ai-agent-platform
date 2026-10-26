package com.platform.model.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.common.security.CryptoService;
import com.platform.model.entity.ModelEntity;
import com.platform.model.entity.Provider;
import com.platform.model.entity.ProviderCredential;
import com.platform.model.mapper.ModelMapper;
import com.platform.model.mapper.ProviderCredentialMapper;
import com.platform.model.mapper.ProviderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Provider / 模型管理（需求文档 3.7）：连通性测试、模型列表同步。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderService {

    private final ProviderMapper providerMapper;
    private final ModelMapper modelMapper;
    private final ProviderCredentialMapper credentialMapper;
    private final CryptoService cryptoService;
    private final Environment env;

    private RestClient client(Provider p, Duration timeout) {
        RestClient.Builder builder = RestClient.builder().baseUrl(p.getBaseUrl());
        String key = resolveApiKey(p);
        String type = p.getProviderType() == null ? "" : p.getProviderType().toUpperCase(Locale.ROOT);
        if ("ANTHROPIC".equals(type)) {
            if (key != null && !key.isBlank()) builder.defaultHeader("x-api-key", key);
            builder.defaultHeader("anthropic-version", "2023-06-01");
        } else if (key != null && !key.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + key);
        }
        return builder.build();
    }

    /**
     * API Key：平台预置 Provider 从环境变量解析（不落库）；租户自建走 provider_credential（AES-GCM 加密）。
     * OLLAMA / OPENAI_COMPATIBLE 允许无 Key（本地服务或内网代理）。
     */
    public String resolveApiKey(Provider p) {
        String type = p.getProviderType() == null ? "" : p.getProviderType().toUpperCase(Locale.ROOT);
        if (p.getTenantId() != null) {
            ProviderCredential c = credentialMapper.selectOne(new LambdaQueryWrapper<ProviderCredential>()
                    .eq(ProviderCredential::getProviderId, p.getId())
                    .eq(ProviderCredential::getName, "api-key")
                    .eq(ProviderCredential::getStatus, "ENABLED")
                    .last("LIMIT 1"));
            if (c != null && c.getEncryptedContent() != null && !c.getEncryptedContent().isBlank()) {
                return cryptoService.decrypt(c.getEncryptedContent());
            }
            if (allowsEmptyKey(type)) return "";
            throw new BusinessException(400, "租户 Provider 未配置 API Key，请在模型接入页填写密钥");
        }
        String envName = switch (type) {
            case "DEEPSEEK" -> "DEEPSEEK_API_KEY";
            case "OPENAI" -> "OPENAI_API_KEY";
            case "DASHSCOPE", "QWEN" -> "DASHSCOPE_API_KEY";
            case "ZHIPU", "GLM" -> "ZHIPU_API_KEY";
            case "MOONSHOT" -> "MOONSHOT_API_KEY";
            case "VOLC" -> "VOLC_API_KEY";
            case "ANTHROPIC" -> "ANTHROPIC_API_KEY";
            default -> null;
        };
        String key = envName == null ? null : env.getProperty(envName);
        if (allowsEmptyKey(type) && (key == null || key.isBlank())) return "";
        if (key == null || key.isBlank() || key.startsWith("sk-not")) {
            throw new BusinessException(400, "Provider「" + p.getName() + "」API Key 未配置"
                    + (envName == null ? "" : "（" + envName + "）"));
        }
        return key;
    }

    private String modelsPath(Provider p) {
        String type = p.getProviderType() == null ? "" : p.getProviderType().toUpperCase(Locale.ROOT);
        String base = p.getBaseUrl() == null ? "" : p.getBaseUrl();
        // baseUrl 已含 /v1 的（如 DeepSeek 官方写法）不再追加
        if (base.endsWith("/v1")) return "/models";
        return "/v1/models";
    }

    public void saveCredential(Long providerId, String apiKey) {
        Provider p = providerMapper.findByIdIgnore(providerId);
        if (p == null) throw new BusinessException(404, "Provider 不存在");
        if (p.getTenantId() == null) {
            throw new BusinessException(403, "平台预置 Provider 请在服务端环境变量配置密钥");
        }
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null || !p.getTenantId().equals(tenantId)) {
            throw new BusinessException(403, "无权修改该 Provider 的密钥");
        }
        ProviderCredential c = credentialMapper.selectOne(new LambdaQueryWrapper<ProviderCredential>()
                .eq(ProviderCredential::getProviderId, providerId)
                .eq(ProviderCredential::getName, "api-key")
                .last("LIMIT 1"));
        if (apiKey == null || apiKey.isBlank()) {
            if (c != null) credentialMapper.deleteById(c.getId());
            return;
        }
        if (c == null) {
            c = new ProviderCredential();
            c.setProviderId(providerId);
            c.setName("api-key");
            c.setTenantId(tenantId);
            c.setStatus("ENABLED");
            c.setCreatedAt(LocalDateTime.now());
            c.setEncryptedContent(cryptoService.encrypt(apiKey.trim()));
            credentialMapper.insert(c);
        } else {
            c.setEncryptedContent(cryptoService.encrypt(apiKey.trim()));
            c.setStatus("ENABLED");
            credentialMapper.updateById(c);
        }
    }

    public boolean hasCredential(Long providerId) {
        Provider p = providerMapper.findByIdIgnore(providerId);
        if (p == null) return false;
        if (p.getTenantId() == null) {
            try {
                return !resolveApiKey(p).isBlank();
            } catch (Exception e) {
                return false;
            }
        }
        return credentialMapper.selectCount(new LambdaQueryWrapper<ProviderCredential>()
                .eq(ProviderCredential::getProviderId, providerId)
                .eq(ProviderCredential::getName, "api-key")
                .eq(ProviderCredential::getStatus, "ENABLED")) > 0;
    }

    private boolean allowsEmptyKey(String type) {
        return "OLLAMA".equals(type) || "OPENAI_COMPATIBLE".equals(type);
    }

    public Map<String, Object> test(Long providerId) {
        Provider p = providerMapper.findByIdIgnore(providerId);
        if (p == null) throw new BusinessException(404, "Provider 不存在");
        long start = System.currentTimeMillis();
        try {
            var resp = client(p, Duration.ofSeconds(15)).get().uri(modelsPath(p))
                    .retrieve().body(Map.class);
            long n = resp != null && resp.get("data") instanceof List<?> l ? l.size() : 0;
            return Map.of("success", true, "latencyMs", System.currentTimeMillis() - start,
                    "message", "连通正常，可见模型 " + n + " 个");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            return Map.of("success", false, "latencyMs", System.currentTimeMillis() - start,
                    "message", "测试失败：" + rootMessage(e));
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> syncModels(Long providerId) {
        Provider p = providerMapper.findByIdIgnore(providerId);
        if (p == null) throw new BusinessException(404, "Provider 不存在");
        Map<String, Object> body;
        try {
            body = client(p, Duration.ofSeconds(30)).get().uri(modelsPath(p))
                    .retrieve().body(Map.class);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "模型列表拉取失败：" + rootMessage(e));
        }
        List<Map<String, Object>> data = body == null ? List.of() : (List<Map<String, Object>>) body.get("data");
        int enabled = 0;
        for (Map<String, Object> m : data) {
            String modelName = String.valueOf(m.get("id"));
            boolean multimodal = m.get("input_modalities") instanceof List<?> mods && mods.contains("image");
            // 用途启发式：名称特征 → VISION（图片理解）/ ASR（语音转文字）/ OCR（文字识别）/ CHAT
            String lower = modelName.toLowerCase(Locale.ROOT);
            String purpose;
            boolean visionLike = lower.contains("-vl") || lower.contains("vision")
                    || lower.contains("llava") || lower.contains("minicpm-v");
            boolean asrLike = lower.contains("whisper") || lower.contains("asr")
                    || lower.contains("sense-voice") || lower.contains("paraformer");
            boolean ocrLike = lower.contains("ocr") || lower.contains("tesseract");
            if (asrLike) purpose = "ASR";
            else if (ocrLike) purpose = "OCR";
            else if (visionLike) purpose = "VISION";
            else purpose = "CHAT";
            ModelEntity existing = providerMapper.listVisibleModels(null).stream()
                    .filter(x -> x.getProviderId().equals(p.getId()) && x.getModelName().equals(modelName))
                    .findFirst().orElse(null);
            if (existing == null) {
                ModelEntity e = new ModelEntity();
                e.setProviderId(p.getId());
                e.setModelName(modelName);
                e.setDisplayName(m.get("name") == null ? modelName : String.valueOf(m.get("name")));
                e.setPurpose(purpose);
                e.setCapabilities(capabilitiesJson(multimodal));
                e.setDefaultParams("{\"temperature\":0.7}");
                e.setStatus("ENABLED");
                e.setTenantId(p.getTenantId()); // 平台预置 Provider 同步出的模型即平台预置
                modelMapper.insertIgnoreTenant(e);
                enabled++;
            } else {
                existing.setDisplayName(m.get("name") == null ? existing.getDisplayName() : String.valueOf(m.get("name")));
                existing.setPurpose(purpose);
                existing.setCapabilities(mergeCapabilities(existing.getCapabilities(), multimodal));
                modelMapper.updateIgnoreTenant(existing);
                enabled++;
            }
        }
        return Map.of("total", data.size(), "enabled", enabled);
    }

    /** 新建/同步模型：默认不声明支持思考，由管理员在「模型接入」里显式开启（不同模型能力不同）。 */
    private String capabilitiesJson(boolean multimodal) {
        return "{\"multimodal\":" + multimodal + ",\"reasoning\":false,\"functionCall\":true}";
    }

    /** 同步时只刷新 multimodal，保留管理员配置的思考能力/级别。 */
    @SuppressWarnings("unchecked")
    private String mergeCapabilities(String raw, boolean multimodal) {
        Map<String, Object> caps = new java.util.LinkedHashMap<>();
        if (raw != null && !raw.isBlank()) {
            try {
                caps.putAll(JSON.readValue(raw, Map.class));
            } catch (Exception ignored) { /* 历史脏数据：按空处理 */ }
        }
        caps.put("multimodal", multimodal);
        caps.putIfAbsent("reasoning", false);
        caps.putIfAbsent("functionCall", true);
        try {
            return JSON.writeValueAsString(caps);
        } catch (Exception e) {
            return capabilitiesJson(multimodal);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private String rootMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
