package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 图表分段构建：超长图表不做字符串截断，而是按系列/数据点/长字符串压缩，
 * 保证写入知识库的仍然是合法 JSON。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeChartChunkBuilder {

    public static final String CHART_CHUNK_MARKER = "【图表数据";
    public static final String CHART_CHUNK_PREFIX = "【图表数据·模型识别，需人工核对】";
    public static final int CHART_CHUNK_MAX = 4000;

    private final ObjectMapper objectMapper;

    public String build(ObjectNode chart, List<String> warnings) throws Exception {
        String originalJson = objectMapper.writeValueAsString(chart);
        if (CHART_CHUNK_PREFIX.length() + originalJson.length() <= CHART_CHUNK_MAX) {
            return CHART_CHUNK_PREFIX + originalJson;
        }
        ObjectNode compact = chart.deepCopy();
        int[] pointLimits = {60, 40, 20, 10};
        int[] seriesLimits = {20, 12, 8, 4};
        for (int i = 0; i < pointLimits.length; i++) {
            truncateSeriesPoints(compact, pointLimits[i]);
            truncateTopLevelPoints(compact, pointLimits[i]);
            truncateSeries(compact, seriesLimits[i]);
            trimStrings(compact, 300);
            String json = objectMapper.writeValueAsString(compact);
            if (CHART_CHUNK_PREFIX.length() + json.length() <= CHART_CHUNK_MAX) {
                if (i > 0) {
                    warnings.add("图表数据过长，已压缩为每个系列前 " + pointLimits[i]
                            + " 个数据点/最多 " + seriesLimits[i] + " 个系列；完整数据请查看原图");
                }
                return CHART_CHUNK_PREFIX + json;
            }
        }
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("isChart", compact.path("isChart").asBoolean(true));
        summary.put("title", compact.path("title").asText(""));
        summary.put("compacted", true);
        summary.put("note", "图表数据过长，已压缩为摘要；完整数据请查看原图");
        if (compact.get("data") instanceof ArrayNode data) summary.put("dataPointCount", data.size());
        if (compact.get("points") instanceof ArrayNode points) summary.put("pointCount", points.size());
        JsonNode series = compact.get("series");
        if (series instanceof ArrayNode array) {
            ArrayNode names = summary.putArray("series");
            for (JsonNode item : array) {
                ObjectNode shortSeries = names.addObject();
                shortSeries.put("name", item.path("name").asText(""));
                JsonNode points = item.has("points") ? item.get("points")
                        : item.has("data") ? item.get("data") : item.get("values");
                shortSeries.put("pointCount", points != null && points.isArray() ? points.size() : 0);
            }
        }
        warnings.add("图表数据过长，已压缩为摘要；完整数据请查看原图");
        return CHART_CHUNK_PREFIX + objectMapper.writeValueAsString(summary);
    }

    private void truncateTopLevelPoints(ObjectNode chart, int limit) {
        for (String key : new String[]{"data", "points"}) {
            JsonNode value = chart.get(key);
            if (!(value instanceof ArrayNode points) || points.size() <= limit) continue;
            int original = points.size();
            while (points.size() > limit) points.remove(points.size() - 1);
            chart.put("originalPointCount", original);
            chart.put("truncatedPoints", original - limit);
        }
    }

    private void truncateSeriesPoints(ObjectNode chart, int limit) {
        JsonNode series = chart.get("series");
        if (!(series instanceof ArrayNode array)) return;
        for (JsonNode item : array) {
            if (!(item instanceof ObjectNode object)) continue;
            for (String key : new String[]{"points", "data", "values"}) {
                JsonNode value = object.get(key);
                if (!(value instanceof ArrayNode points) || points.size() <= limit) continue;
                int original = points.size();
                while (points.size() > limit) points.remove(points.size() - 1);
                object.put("originalPointCount", original);
                object.put("truncatedPoints", original - limit);
            }
        }
    }

    private void truncateSeries(ObjectNode chart, int limit) {
        JsonNode series = chart.get("series");
        if (!(series instanceof ArrayNode array) || array.size() <= limit) return;
        int original = array.size();
        while (array.size() > limit) array.remove(array.size() - 1);
        chart.put("originalSeriesCount", original);
        chart.put("omittedSeries", original - limit);
    }

    private void trimStrings(JsonNode node, int maxLength) {
        if (node instanceof ObjectNode object) {
            var fields = object.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                JsonNode value = entry.getValue();
                if (value.isTextual() && value.asText().length() > maxLength) {
                    object.put(entry.getKey(), value.asText().substring(0, maxLength) + "…");
                } else {
                    trimStrings(value, maxLength);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (JsonNode item : array) trimStrings(item, maxLength);
        }
    }

    public boolean isChartChunk(String content) {
        return content != null && content.startsWith(CHART_CHUNK_MARKER);
    }
}
