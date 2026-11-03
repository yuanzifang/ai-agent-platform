package com.platform.workflow.service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Workflow 知识库节点检索配置构造，避免主服务继续膨胀。 */
final class WorkflowRetrievalOptions {

    private WorkflowRetrievalOptions() {
    }

    static Map<String, Object> from(Map<String, Object> node, String mode) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        if (mode != null && !mode.isBlank()) cfg.put("mode", mode);
        if (node.containsKey("rerank")) cfg.put("rerank", Boolean.TRUE.equals(node.get("rerank")));
        int rerankTopN = intValue(node.get("rerankTopN"));
        if (rerankTopN > 0) cfg.put("rerankTopN", rerankTopN);
        return cfg;
    }

    static Map<String, Object> knowledgeNode(String id, String next) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", "KNOWLEDGE");
        node.put("name", "知识库检索");
        node.put("mode", "hybrid");
        node.put("topK", 5);
        node.put("rerank", true);
        node.put("rerankTopN", 5);
        node.put("queryTemplate", "{{input}}");
        node.put("next", next);
        return node;
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
