package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图表分段 JSON 的轻量结构校验：检查可解析、series/data/points 类型及数值格式。
 * 不校验图表数值是否与原始图片真实一致；该部分仍需人工复核。
 */
final class ChartDataValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NUMBER = "[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?";
    private static final Pattern EVIDENCE_NUMBER = Pattern.compile("[-+]?\\d[\\d,]*(?:\\.\\d+)?%?");

    private ChartDataValidator() {
    }

    record CrossCheck(String status, int checked, int missing, List<String> missingPoints) {
    }

    private record Point(String x, String value) { }

    /** 与文档文本/OCR 中的数字逐点比对：有 x 标签时要求在标签邻近窗口出现 y 值。 */
    static CrossCheck crossCheck(JsonNode chart, String evidence) {
        List<Point> points = new ArrayList<>();
        collectPoints(chart, points);
        if (points.isEmpty()) return new CrossCheck("unverified", 0, 0, List.of());
        String text = evidence == null ? "" : evidence;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        Set<String> evidenceNumbers = new LinkedHashSet<>();
        Matcher matcher = EVIDENCE_NUMBER.matcher(text);
        while (matcher.find()) {
            String normalized = normalize(matcher.group());
            if (normalized != null) evidenceNumbers.add(normalized);
        }
        int checked = 0;
        int missing = 0;
        boolean labelUnavailable = false;
        List<String> missingPoints = new ArrayList<>();
        for (Point point : points) {
            String value = normalize(point.value());
            if (value == null) continue;
            checked++;
            String x = point.x() == null ? "" : point.x().trim();
            if (x.isBlank()) {
                if (!evidenceNumbers.contains(value)) {
                    missing++;
                    if (missingPoints.size() < 20) missingPoints.add(value);
                }
                continue;
            }
            int at = lower.indexOf(x.toLowerCase(java.util.Locale.ROOT));
            if (at < 0) {
                labelUnavailable = true;
                missing++;
                continue;
            }
            int end = Math.min(text.length(), at + x.length() + 120);
            String window = text.substring(Math.max(0, at - 20), end);
            Set<String> nearby = new LinkedHashSet<>();
            Matcher nearbyMatcher = EVIDENCE_NUMBER.matcher(window);
            while (nearbyMatcher.find()) {
                String normalized = normalize(nearbyMatcher.group());
                if (normalized != null) nearby.add(normalized);
            }
            if (!nearby.contains(value)) {
                missing++;
                if (missingPoints.size() < 20) missingPoints.add(x + "=" + value);
            }
        }
        if (evidenceNumbers.isEmpty()) {
            return new CrossCheck("unverified", checked, checked,
                    points.stream().map(Point::value).limit(20).toList());
        }
        if (labelUnavailable) return new CrossCheck("unverified", checked, missing, missingPoints);
        return new CrossCheck(missing == 0 ? "verified" : "conflict", checked, missing, missingPoints);
    }

    private static void collectPoints(JsonNode node, List<Point> points) {
        if (node == null || node.isNull()) return;
        if (node.isArray()) {
            for (JsonNode item : node) collectPoints(item, points);
            return;
        }
        if (!node.isObject()) return;
        JsonNode value = node.get("value") != null ? node.get("value") : node.get("y");
        if (value != null && value.isValueNode()) {
            String x = node.has("x") ? node.get("x").asText()
                    : node.has("label") ? node.get("label").asText() : null;
            points.add(new Point(x, value.asText()));
            return;
        }
        for (String key : new String[]{"series", "data", "points", "values"}) {
            if (node.has(key)) collectPoints(node.get(key), points);
        }
    }

    private static String normalize(String raw) {
        if (raw == null) return null;
        String value = raw.trim().replace(",", "").replace("%", "");
        try {
            return new BigDecimal(value).stripTrailingZeros().toPlainString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 返回 null 表示通过，否则返回可展示的错误原因。 */
    static String validate(String content) {
        if (content == null || !content.startsWith("【图表数据")) return "缺少【图表数据】标记";
        int end = content.indexOf('】');
        if (end < 0) return "图表数据标记不完整";
        String body = content.substring(end + 1).trim();
        if (body.isBlank()) return "图表数据为空";
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            return "图表数据不是合法 JSON";
        }
        if (root == null || !root.isObject()) return "图表数据必须是 JSON 对象";
        boolean hasStructure = root.has("series") || root.has("data")
                || root.has("points") || root.has("isChart");
        if (!hasStructure) return "图表数据缺少 series/data/points 字段";
        if (root.has("isChart") && !root.get("isChart").isBoolean()
                && !root.get("isChart").isTextual()) {
            return "isChart 必须是布尔值";
        }
        String error = validateNode(root.get("series"), "series");
        if (error != null) return error;
        error = validateNode(root.get("data"), "data");
        if (error != null) return error;
        return validateNode(root.get("points"), "points");
    }

    private static String validateNode(JsonNode node, String path) {
        if (node == null || node.isNull()) return null;
        if (!node.isArray()) return path + " 必须是数组";
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            String error = validateItem(item, path + "[" + i + "]");
            if (error != null) return error;
        }
        return null;
    }

    private static String validateItem(JsonNode item, String path) {
        if (item == null || item.isNull() || item.isBoolean()) return null;
        if (item.isNumber()) return null;
        if (item.isTextual()) {
            return item.asText().matches(NUMBER) || item.asText().isBlank()
                    ? null : path + " 不是合法数值";
        }
        if (item.isArray()) {
            for (int i = 0; i < item.size(); i++) {
                String error = validateItem(item.get(i), path + "[" + i + "]");
                if (error != null) return error;
            }
            return null;
        }
        if (!item.isObject()) return path + " 格式不支持";
        for (String key : new String[]{"points", "data", "values"}) {
            if (item.has(key)) {
                String error = validateNode(item.get(key), path + "." + key);
                if (error != null) return error;
            }
        }
        for (String key : new String[]{"value", "y"}) {
            if (item.has(key)) {
                String error = validateItem(item.get(key), path + "." + key);
                if (error != null) return error;
            }
        }
        // x 轴标签允许文本（如 “一月”“Q1”），只拒绝对象/数组等非标量。
        JsonNode x = item.get("x");
        if (x != null && !x.isNull() && !x.isNumber() && !x.isTextual() && !x.isBoolean()) {
            return path + ".x 格式不支持";
        }
        return null;
    }
}
