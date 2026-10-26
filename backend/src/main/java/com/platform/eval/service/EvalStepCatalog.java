package com.platform.eval.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 步骤级黄金集的类型目录与最小结构校验。 */
public final class EvalStepCatalog {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvalStepCatalog() {
    }

    public static List<Map<String, Object>> definitions() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(definition("ANSWER", "最终回答", List.of(), List.of(), List.of("LLM 裁判")));
        rows.add(definition("REWRITE", "查询改写", List.of(), List.of("rewrite 或 must_keep"),
                List.of("exact/实体保留")));
        rows.add(definition("RETRIEVAL", "知识检索", List.of("knowledgeBaseId"),
                List.of("chunk_ids"), List.of("recall")));
        rows.add(definition("RERANK", "重排", List.of("knowledgeBaseId"),
                List.of("ordered_chunk_ids"), List.of("expectedFirstRank")));
        return rows;
    }

    private static Map<String, Object> definition(String type, String name, List<String> requiredConfig,
                                                   List<String> requiredExpected, List<String> metrics) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stepType", type);
        row.put("name", name);
        row.put("requiredConfig", requiredConfig);
        row.put("requiredExpected", requiredExpected);
        row.put("metrics", metrics);
        return row;
    }

    /** 返回 null 表示通过；否则返回可直接展示的中文错误。 */
    public static String validate(String stepType, String expectedPayload, String stepConfig) {
        String type = stepType == null ? "ANSWER" : stepType.trim().toUpperCase(Locale.ROOT);
        Map<String, Object> expected;
        Map<String, Object> config;
        try {
            expected = parse(expectedPayload);
            config = parse(stepConfig);
        } catch (Exception e) {
            return "expectedPayload/stepConfig 必须是合法 JSON";
        }
        return switch (type) {
            case "ANSWER" -> null;
            case "REWRITE" -> {
                if (str(expected.get("rewrite")).isBlank()
                        && !(expected.get("must_keep") instanceof List<?> list && !list.isEmpty())) {
                    yield "改写步骤需要 rewrite 或 must_keep";
                }
                yield null;
            }
            case "RETRIEVAL" -> {
                if (number(config.get("knowledgeBaseId")) == null) yield "检索步骤缺少 knowledgeBaseId";
                if (!nonEmptyList(expected.get("chunk_ids"))) yield "检索步骤缺少 chunk_ids";
                yield null;
            }
            case "RERANK" -> {
                if (number(config.get("knowledgeBaseId")) == null) yield "重排步骤缺少 knowledgeBaseId";
                if (!nonEmptyList(expected.get("ordered_chunk_ids"))) yield "重排步骤缺少 ordered_chunk_ids";
                yield null;
            }
            default -> "不支持的步骤类型：" + stepType;
        };
    }

    private static Map<String, Object> parse(String json) throws Exception {
        if (json == null || json.isBlank()) return Map.of();
        return MAPPER.readValue(json, Map.class);
    }

    private static boolean nonEmptyList(Object value) {
        return value instanceof List<?> list && !list.isEmpty();
    }

    private static Long number(Object value) {
        if (value instanceof Number n) return n.longValue();
        try { return value == null ? null : Long.parseLong(String.valueOf(value)); }
        catch (Exception e) { return null; }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
