package com.platform.model.service;

import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.model.entity.ModelEntity;
import com.platform.model.entity.Provider;
import com.platform.model.mapper.ModelMapper;
import com.platform.model.mapper.ProviderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
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
    private final Environment env;

    private RestClient client(Provider p, Duration timeout) {
        return RestClient.builder()
                .baseUrl(p.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + resolveApiKey(p))
                .build();
    }

    /** API Key：平台预置 Provider 从环境变量解析（不落库）；租户自建走 provider_credential（M2+）。 */
    public String resolveApiKey(Provider p) {
        if ("DEEPSEEK".equals(p.getProviderType())) {
            String key = env.getProperty("DEEPSEEK_API_KEY");
            if (key == null || key.isBlank() || key.startsWith("sk-not")) {
                throw new BusinessException(400, "DeepSeek API Key 未配置（DEEPSEEK_API_KEY）");
            }
            return key;
        }
        throw new BusinessException(400, "暂不支持的 Provider 类型：" + p.getProviderType());
    }

    public Map<String, Object> test(Long providerId) {
        Provider p = providerMapper.findByIdIgnore(providerId);
        if (p == null) throw new BusinessException(404, "Provider 不存在");
        long start = System.currentTimeMillis();
        try {
            var resp = client(p, Duration.ofSeconds(15)).get().uri("/models")
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
            body = client(p, Duration.ofSeconds(30)).get().uri("/models")
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
            ModelEntity existing = providerMapper.listVisibleModels(null).stream()
                    .filter(x -> x.getProviderId().equals(p.getId()) && x.getModelName().equals(modelName))
                    .findFirst().orElse(null);
            if (existing == null) {
                ModelEntity e = new ModelEntity();
                e.setProviderId(p.getId());
                e.setModelName(modelName);
                e.setDisplayName(m.get("name") == null ? modelName : String.valueOf(m.get("name")));
                e.setPurpose("CHAT");
                e.setCapabilities(capabilitiesJson(multimodal));
                e.setDefaultParams("{\"temperature\":0.7}");
                e.setStatus("ENABLED");
                e.setTenantId(p.getTenantId()); // 平台预置 Provider 同步出的模型即平台预置
                modelMapper.insertIgnoreTenant(e);
                enabled++;
            } else {
                existing.setDisplayName(m.get("name") == null ? existing.getDisplayName() : String.valueOf(m.get("name")));
                existing.setCapabilities(capabilitiesJson(multimodal));
                modelMapper.updateIgnoreTenant(existing);
                enabled++;
            }
        }
        return Map.of("total", data.size(), "enabled", enabled);
    }

    private String capabilitiesJson(boolean multimodal) {
        return "{\"multimodal\":" + multimodal + ",\"reasoning\":true,\"functionCall\":true}";
    }

    private String rootMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
