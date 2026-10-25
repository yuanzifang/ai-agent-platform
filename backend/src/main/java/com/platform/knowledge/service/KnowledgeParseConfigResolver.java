package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.context.TenantContext;
import com.platform.system.entity.SysConfig;
import com.platform.system.entity.SysTenantConfig;
import com.platform.system.mapper.SysConfigMapper;
import com.platform.system.mapper.SysTenantConfigMapper;
import com.platform.knowledge.entity.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解析配置四级解析器（评审设计）：env → 平台(sys_config) → 租户(sys_tenant_config) → 知识库(parse_config)
 * → 文档(上传时选择)。低级别未设置的键沿用高级别；安全键（ocrCommand/visionEndpoint）只允许
 * env 与平台级，不参与知识库/文档覆盖。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeParseConfigResolver {

    /** 可被知识库/文档级覆盖的安全键。 */
    private static final Map<String, String> SAFE_KEYS = Map.of(
            "ocrEnabled", "knowledge.parse.ocr-enabled",
            "ocrLanguages", "knowledge.parse.ocr-languages",
            "ocrDpi", "knowledge.parse.ocr-dpi",
            "ocrMaxPages", "knowledge.parse.ocr-max-pages",
            "visionEnabled", "knowledge.parse.vision-enabled",
            "visionModel", "knowledge.parse.vision-model",
            "visionMaxImages", "knowledge.parse.vision-max-images");

    /** 仅平台级键（避免 SSRF/命令注入）。 */
    private static final String PLATFORM_VISION_ENDPOINT = "knowledge.vision.endpoint";

    private final SysConfigMapper sysConfigMapper;
    private final SysTenantConfigMapper tenantConfigMapper;
    private final ObjectMapper objectMapper;

    @Value("${knowledge.ocr.enabled:false}")
    private boolean envOcrEnabled;
    @Value("${knowledge.ocr.command:tesseract}")
    private String envOcrCommand;
    @Value("${knowledge.ocr.languages:chi_sim+eng}")
    private String envOcrLanguages;
    @Value("${knowledge.ocr.dpi:150}")
    private int envOcrDpi;
    @Value("${knowledge.ocr.max-pages:20}")
    private int envOcrMaxPages;
    @Value("${knowledge.vision.enabled:false}")
    private boolean envVisionEnabled;
    @Value("${knowledge.vision.endpoint:http://127.0.0.1:11434}")
    private String envVisionEndpoint;
    @Value("${knowledge.vision.model:qwen3-vl:32b}")
    private String envVisionModel;
    @Value("${knowledge.vision.max-images:3}")
    private int envVisionMaxImages;

    /** 解析最终生效配置。docParseConfigJson 为文档级覆盖（可空）。 */
    public ParseOptions resolve(KnowledgeBase kb, String docParseConfigJson) {
        Long tenantId = kb == null ? TenantContext.getTenantId() : kb.getTenantId();
        Map<String, String> merged = new LinkedHashMap<>();
        // 1) env 基线
        merged.put("ocrEnabled", String.valueOf(envOcrEnabled));
        merged.put("ocrLanguages", envOcrLanguages);
        merged.put("ocrDpi", String.valueOf(envOcrDpi));
        merged.put("ocrMaxPages", String.valueOf(envOcrMaxPages));
        merged.put("visionEnabled", String.valueOf(envVisionEnabled));
        merged.put("visionModel", envVisionModel);
        merged.put("visionMaxImages", String.valueOf(envVisionMaxImages));
        merged.put("visionEndpoint", envVisionEndpoint);
        merged.put("ocrCommand", envOcrCommand);
        // 2) 平台 sys_config（含 visionEndpoint 平台键 + 安全键的平台默认）
        applyLayer(merged, platformValues());
        // 3) 租户 sys_tenant_config（仅安全键）
        if (tenantId != null) {
            applyLayer(merged, tenantValues(tenantId));
        }
        // 4) 知识库 parse_config（仅安全键）
        if (kb != null) {
            applyLayer(merged, jsonValues(kb.getParseConfig()));
        }
        // 5) 文档 parse_config（仅安全键，上传时选择，优先级最高）
        if (docParseConfigJson != null && !docParseConfigJson.isBlank()) {
            applyLayer(merged, jsonValues(docParseConfigJson));
        }
        return new ParseOptions(
                Boolean.parseBoolean(merged.get("ocrEnabled")),
                merged.get("ocrCommand"),
                merged.get("ocrLanguages"),
                intOf(merged.get("ocrDpi"), envOcrDpi),
                intOf(merged.get("ocrMaxPages"), envOcrMaxPages),
                Boolean.parseBoolean(merged.get("visionEnabled")),
                merged.get("visionEndpoint"),
                merged.get("visionModel"),
                intOf(merged.get("visionMaxImages"), envVisionMaxImages));
    }

    /** 供前端「跟随系统默认」展示：平台级默认值（不含 KB/文档覆盖）。 */
    public Map<String, Object> platformDefaults() {
        Map<String, String> merged = new LinkedHashMap<>(Map.of(
                "ocrEnabled", String.valueOf(envOcrEnabled),
                "ocrLanguages", envOcrLanguages,
                "ocrDpi", String.valueOf(envOcrDpi),
                "ocrMaxPages", String.valueOf(envOcrMaxPages),
                "visionEnabled", String.valueOf(envVisionEnabled),
                "visionModel", envVisionModel,
                "visionMaxImages", String.valueOf(envVisionMaxImages)));
        applyLayer(merged, platformValues());
        Map<String, Object> out = new LinkedHashMap<>();
        merged.forEach((k, v) -> out.put(k, "true".equalsIgnoreCase(v) ? Boolean.TRUE
                : "false".equalsIgnoreCase(v) ? Boolean.FALSE : tryInt(v)));
        return out;
    }

    private void applyLayer(Map<String, String> merged, Map<String, String> layer) {
        if (layer == null) return;
        // 兼容两种键形：平台层用全键（knowledge.parse.*），KB/文档层 JSON 用短键（ocrEnabled 等）
        SAFE_KEYS.forEach((shortKey, fullKey) -> {
            String v = layer.containsKey(fullKey) ? layer.get(fullKey) : layer.get(shortKey);
            if (v != null && !v.isBlank()) merged.put(shortKey, normalize(v));
        });
        String endpoint = layer.containsKey(PLATFORM_VISION_ENDPOINT) ? layer.get(PLATFORM_VISION_ENDPOINT)
                : layer.get("visionEndpoint");
        if (endpoint != null && !endpoint.isBlank()) merged.put("visionEndpoint", endpoint);
    }

    private String normalize(String v) {
        // sys_config JSON 列取出后可能带引号（"true"），统一剥引号
        String t = v.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1);
        return t.trim();
    }

    private Map<String, String> platformValues() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (SysConfig c : sysConfigMapper.selectList(null)) {
                if (c.getConfigKey() != null) out.put(c.getConfigKey(), c.getConfigValue());
            }
        } catch (Exception ignored) { }
        return out;
    }

    private Map<String, String> tenantValues(Long tenantId) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (SysTenantConfig c : tenantConfigMapper.selectList(
                    new LambdaQueryWrapper<SysTenantConfig>().eq(SysTenantConfig::getTenantId, tenantId))) {
                if (c.getConfigKey() != null) out.put(c.getConfigKey(), c.getConfigValue());
            }
        } catch (Exception ignored) { }
        return out;
    }

    private Map<String, String> jsonValues(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> raw = objectMapper.readValue(json, Map.class);
            Map<String, String> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> out.put(k, v == null ? null : String.valueOf(v)));
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Object tryInt(String v) {
        try { return Integer.parseInt(v); } catch (Exception e) { return v; }
    }

    private int intOf(String v, int fallback) {
        try { return Integer.parseInt(v); } catch (Exception e) { return fallback; }
    }
}
