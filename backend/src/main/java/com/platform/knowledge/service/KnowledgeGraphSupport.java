package com.platform.knowledge.service;

import com.platform.common.exception.BusinessException;
import com.platform.knowledge.entity.KnowledgeChunk;
import com.platform.knowledge.entity.KnowledgeGraphJob;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** KnowledgeGraphService 的无状态解析/规范化工具，集中放置避免主服务继续膨胀。 */
public final class KnowledgeGraphSupport {

    private KnowledgeGraphSupport() {
    }

    public static Map<String, Object> jobMap(KnowledgeGraphJob job) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", job.getId());
        map.put("kbId", job.getKbId());
        map.put("status", job.getStatus());
        map.put("totalDocuments", job.getTotalDocuments());
        map.put("processedDocuments", job.getProcessedDocuments());
        map.put("totalChunks", job.getTotalChunks());
        map.put("processedChunks", job.getProcessedChunks());
        map.put("entityCount", job.getEntityCount());
        map.put("relationCount", job.getRelationCount());
        map.put("message", job.getMessage());
        map.put("error", job.getError());
        map.put("createdAt", job.getCreatedAt());
        map.put("startedAt", job.getStartedAt());
        map.put("finishedAt", job.getFinishedAt());
        return map;
    }

    public static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static String stripFence(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("```")) {
            int first = text.indexOf('\n');
            int last = text.lastIndexOf("```");
            if (first >= 0 && last > first) text = text.substring(first + 1, last).trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }

    public static String normalizeName(String name) {
        if (name == null) return "";
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{S}\\s]", "");
        if (normalized.isBlank()) normalized = name.trim().toLowerCase(Locale.ROOT);
        return normalized.length() > 128 ? normalized.substring(0, 128) : normalized;
    }

    public static String normalizeType(String type) {
        String value = type == null ? "" : type.trim();
        return value.isEmpty() ? "其他" : value;
    }

    public static BigDecimal confidence(Object raw) {
        double value = 0.8;
        if (raw instanceof Number n) {
            value = n.doubleValue();
        } else if (raw != null && !String.valueOf(raw).isBlank()) {
            try {
                value = Double.parseDouble(String.valueOf(raw));
            } catch (NumberFormatException ignored) {
                // 默认置信度
            }
        }
        if (!Double.isFinite(value)) value = 0.8;
        value = Math.max(0.0, Math.min(1.0, value));
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
    }

    public static String text(Object raw, int max) {
        if (raw == null) return null;
        String value = String.valueOf(raw).trim();
        if (value.isEmpty()) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }

    public static String limit(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }

    public static Long number(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        try {
            return Long.valueOf(String.valueOf(raw));
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "ID 格式错误");
        }
    }

    public static BigDecimal decimal(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        try {
            return new BigDecimal(String.valueOf(raw));
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "权重格式错误");
        }
    }

    public static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    public static KnowledgeChunk chunkAt(List<KnowledgeChunk> chunks, int number) {
        int index = Math.max(0, Math.min(chunks.size() - 1, number - 1));
        return chunks.get(index);
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> maps(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                map.forEach((k, v) -> m.put(String.valueOf(k), v));
                result.add(m);
            }
        }
        return result;
    }

}
