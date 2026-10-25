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

    record CrossCheck(String status, int checked, int missing) {
    }

    /** 与文档文本/OCR 中的数字交叉比对；只标记验证状态，不判定图表事实为真。 */
    static CrossCheck crossCheck(JsonNode chart, String evidence) {
        List<String> values = new ArrayList<>();
        collectChartValues(chart, values);
        if (values.isEmpty()) return new CrossCheck("unverified", 0, 0);
        Set<String> evidenceNumbers = new LinkedHashSet<>();
        Matcher matcher = EVIDENCE_NUMBER.matcher(evidence == null ? "" : evidence);
        while (matcher.find()) {
            String normalized = normalize(matcher.group());
            if (normalized != null) evidenceNumbers.add(normalized);
        }
        int missing = 0;
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized == null) continue;
            if (!evidenceNumbers.contains(normalized)) missing++;
        }
        if (evidenceNumbers.isEmpty()) return new CrossCheck("unverified", values.size(), values.size());
        return new CrossCheck(missing == 0 ? "verified" : "conflict", values.size(), missing);
    }

    private static void collectChartValues(JsonNode node, List<String> values) {
        if (node == null || node.isNull()) return;
        if (node.isValueNode()) {
            values.add(node.asText());
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) collectChartValues(item, values);
            return;
        }
        if (!node.isObject()) return;
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (("value".equals(key) || "y".equals(key)) && value.isValueNode()) {
                values.add(value.asText());
            } else if ("series".equals(key) || "data".equals(key) || "points".equals(key)
                    || "values".equals(key)) {
                collectChartValues(value, values);
            }
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
        for (String key : new String[]{"value", "y", "x"}) {
            if (item.has(key)) {
                String error = validateItem(item.get(key), path + "." + key);
                if (error != null) return error;
            }
        }
        return null;
    }
}
