package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 入库阶段的本地模型 section 分类。
 *
 * <p>只负责给 chunk 标 action/forecast/background/table/policy/other；
 * 模型不可用、超时或输出非法时返回空 map，调用方继续使用规则分类，绝不阻断解析。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SectionTypeClassifier {

    public static final String ACTION = "action";
    public static final String FORECAST = "forecast";
    public static final String BACKGROUND = "background";
    public static final String TABLE = "table";
    public static final String POLICY = "policy";
    public static final String OTHER = "other";

    private static final List<String> LABELS = List.of(
            ACTION, FORECAST, BACKGROUND, TABLE, POLICY, OTHER);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${knowledge.section-classifier.enabled:false}")
    private boolean enabled;

    @Value("${knowledge.section-classifier.endpoint:${OLLAMA_ENDPOINT:http://127.0.0.1:11434}}")
    private String endpoint;

    @Value("${knowledge.section-classifier.model:qwen2.5:1.5b}")
    private String model;

    @Value("${knowledge.section-classifier.batch-size:10}")
    private int batchSize;

    @Value("${knowledge.section-classifier.timeout-seconds:30}")
    private int timeoutSeconds;

    public Map<Integer, String> classify(List<DocumentParser.ParsedChunk> chunks) {
        if (!enabled || chunks == null || chunks.isEmpty() || endpoint == null || endpoint.isBlank()) {
            return Map.of();
        }
        int batch = Math.max(1, Math.min(batchSize, 20));
        Map<Integer, String> result = new LinkedHashMap<>();
        for (int from = 0; from < chunks.size(); from += batch) {
            int to = Math.min(chunks.size(), from + batch);
            List<DocumentParser.ParsedChunk> slice = chunks.subList(from, to);
            result.putAll(classifyBatch(slice, from));
        }
        return result;
    }

    private Map<Integer, String> classifyBatch(List<DocumentParser.ParsedChunk> chunks, int offset) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是农业知识库分段分类器。只输出 JSON 数组，不要 Markdown。\n")
                .append("为每个片段从以下标签中选一个：\n")
                .append("section 必须且只能是英文：action、forecast、background、table、policy、other。\n")
                .append("action=防治措施/用药/田间管理/补救; forecast=预测/趋势/发生动态; ")
                .append("background=症状/危害/病原/识别; table=表格; policy=政策/方案目标; other=其他。\n")
                .append("输出格式：[{\"index\":1,\"section\":\"action\"}, ...]，index 从 1 开始。\n\n");
        for (int i = 0; i < chunks.size(); i++) {
            String text = chunks.get(i).content() == null ? "" : chunks.get(i).content();
            if (text.length() > 600) text = text.substring(0, 600);
            prompt.append("【").append(i + 1).append("】").append(text.trim()).append("\n\n");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("stream", false);
            // 不用 Ollama 的 format=json：该参数会把 1.5B 模型限制成单个对象；
            // 普通 JSON 输出可稳定返回数组，stripFence 兼容 Markdown 代码块。
            body.put("options", Map.of("temperature", 0.0, "num_predict", 1200));
            body.put("messages", List.of(Map.of("role", "user", "content", prompt.toString())));
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint.replaceAll("/+$", "") + "/api/chat"))
                    .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.debug("section classifier HTTP {}: {}", resp.statusCode(),
                        resp.body() == null ? "" : resp.body().substring(0, Math.min(200, resp.body().length())));
                return Map.of();
            }
            String content = objectMapper.readTree(resp.body()).path("message").path("content").asText("");
            Map<Integer, String> labels = parseLabels(content, offset, chunks.size());
            if (!labels.isEmpty()) {
                log.debug("section classifier: batch offset={} count={} labels={}", offset, chunks.size(), labels);
            }
            return labels;
        } catch (Exception e) {
            log.debug("section classifier 失败（回退规则分类，offset={}）：{}", offset, e.getMessage());
            return Map.of();
        }
    }

    private Map<Integer, String> parseLabels(String raw, int offset, int size) {
        Map<Integer, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return result;
        try {
            JsonNode root = objectMapper.readTree(stripFence(raw));
            JsonNode array = root.isArray() ? root : root.path("items");
            if (!array.isArray()) array = root.path("results");
            if (!array.isArray()) array = root.path("sections");
            if (!array.isArray()) return result;
            for (JsonNode item : array) {
                int index = item.path("index").asInt(-1) - 1;
                String section = normalizeSection(item.path("section").asText(""));
                if (index < 0 || index >= size || !LABELS.contains(section)) continue;
                result.put(offset + index, section);
            }
        } catch (Exception e) {
            log.debug("section classifier JSON 解析失败：{}", e.getMessage());
        }
        return result;
    }

    static String normalizeSection(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase();
        if (LABELS.contains(value)) return value;
        if (value.contains("防治") || value.contains("用药") || value.contains("管理")) return ACTION;
        if (value.contains("预测") || value.contains("趋势") || value.contains("动态")) return FORECAST;
        if (value.contains("症状") || value.contains("危害") || value.contains("病原") || value.contains("识别")) {
            return BACKGROUND;
        }
        if (value.contains("表")) return TABLE;
        if (value.contains("政策") || value.contains("方案") || value.contains("目标")) return POLICY;
        return OTHER;
    }

    private String stripFence(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int first = s.indexOf('\n');
            int last = s.lastIndexOf("```");
            if (first >= 0) s = s.substring(first + 1, last > first ? last : s.length());
        }
        return s.trim();
    }
}
