package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可选图片多模态理解：调用本地 Ollama 视觉模型，把图片描述作为独立知识分段。
 * 默认关闭，失败只记录日志，不影响正文/OCR 入库。
 */
@Slf4j
@Service
public class ImageUnderstandingService {

    @Value("${knowledge.vision.enabled:false}")
    private boolean enabled;

    @Value("${knowledge.vision.endpoint:http://127.0.0.1:11434}")
    private String endpoint;

    @Value("${knowledge.vision.model:qwen3-vl:32b}")
    private String model;

    @Value("${knowledge.vision.timeout-seconds:600}")
    private int timeoutSeconds;

    @Value("${knowledge.vision.max-images:3}")
    private int maxImages;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public ImageUnderstandingService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean enabled() {
        return enabled;
    }

    public int maxImages() {
        return Math.max(1, Math.min(maxImages, 10));
    }

    /**
     * 图表结构化（best-effort）：调用视觉模型输出 JSON；返回值仅作为检索线索，
     * 不是精确数据源，入库时必须标记需人工核对。
     */
    public JsonNode extractChartData(byte[] data, String extension, ParseOptions overrides) {
        boolean effEnabled = overrides != null ? overrides.visionEnabled() : enabled;
        String effModel = overrides != null && overrides.visionModel() != null && !overrides.visionModel().isBlank()
                ? overrides.visionModel() : model;
        if (!effEnabled || data == null || data.length == 0) return null;
        try {
            String prompt = "只输出一个 JSON 对象，不要 Markdown、不要解释。格式："
                    + "{\"isChart\":true,\"title\":\"\",\"xAxis\":\"\",\"yAxis\":\"\","
                    + "\"series\":[{\"name\":\"\",\"points\":[{\"label\":\"\",\"value\":\"\"}]}]}。"
                    + "没有坐标轴/图例/数据系列时输出 {\"isChart\":false}。数值看不清就留空，不要猜。";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", effModel);
            body.put("prompt", prompt);
            body.put("images", List.of(Base64.getEncoder().encodeToString(data)));
            body.put("format", "json");
            body.put("stream", false);
            body.put("options", Map.of("temperature", 0.1));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint.replaceAll("/+$", "") + "/api/generate"))
                    .timeout(Duration.ofSeconds(Math.max(10, timeoutSeconds)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            JsonNode outer = objectMapper.readTree(response.body());
            String text = outer.path("response").asText("").trim();
            if (text.startsWith("```")) {
                int first = text.indexOf('{');
                int last = text.lastIndexOf('}');
                if (first >= 0 && last > first) text = text.substring(first, last + 1);
            }
            System.out.println("[CHART-RAW] " + text);
            JsonNode dataNode = objectMapper.readTree(text);
            return dataNode.path("isChart").asBoolean(false) ? dataNode : null;
        } catch (Exception e) {
            log.debug("图表结构化失败（忽略）：{}", e.getMessage());
            return null;
        }
    }

    /** 返回空串表示未启用或失败；调用方按空描述跳过。 */
    public String describe(byte[] data, String extension) {
        return describe(data, extension, null);
    }

    /** 支持解析级覆盖（知识库/文档解析设置）：覆盖启用开关/模型；endpoint 仅平台级不开放覆盖。 */
    public String describe(byte[] data, String extension, ParseOptions overrides) {
        boolean effEnabled = overrides != null ? overrides.visionEnabled() : enabled;
        String effModel = overrides != null && overrides.visionModel() != null && !overrides.visionModel().isBlank()
                ? overrides.visionModel() : model;
        if (!effEnabled || data == null || data.length == 0) return "";
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", effModel);
            body.put("prompt", "请先判断图片类型：只有明显存在坐标轴、图例或数据系列时才归为图表；"
                    + "风景、人物、物体、纯图形归为照片/插图；以文字/表格为主的页面归为扫描文档。"
                    + "图表：提取标题、坐标轴、图例、系列、数据点或趋势；"
                    + "照片/插图：描述场景、主要对象、人物/物体和可见文字；"
                    + "扫描文档：提取标题、表格和关键字段。用中文回答，不超过300字。");
            body.put("images", List.of(Base64.getEncoder().encodeToString(data)));
            body.put("stream", false);
            body.put("options", Map.of("temperature", 0.1));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint.replaceAll("/+$", "") + "/api/generate"))
                    .timeout(Duration.ofSeconds(Math.max(10, timeoutSeconds)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("图片多模态理解失败：status={} body={}", response.statusCode(),
                        response.body() == null ? "" : response.body().substring(0,
                                Math.min(response.body().length(), 200)));
                return "";
            }
            JsonNode node = objectMapper.readTree(response.body());
            String text = node.path("response").asText("").trim();
            if (text.length() > 800) text = text.substring(0, 800);
            log.info("图片多模态理解完成：model={} bytes={} descLength={}", model, data.length, text.length());
            return text;
        } catch (Exception e) {
            log.warn("图片多模态理解异常（忽略）：{}", e.getMessage());
            return "";
        }
    }
}
