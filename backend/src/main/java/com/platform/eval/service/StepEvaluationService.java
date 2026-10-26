package com.platform.eval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.knowledge.service.HybridRetrievalService;
import com.platform.knowledge.service.KnowledgePermissionService;
import com.platform.knowledge.service.RerankService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 步骤级确定性评测：检索、重排、改写；不依赖外部 JEV。 */
@Service
@RequiredArgsConstructor
public class StepEvaluationService {

    private final HybridRetrievalService retrievalService;
    private final RerankService rerankService;
    private final QueryRewriteService rewriteService;
    private final KnowledgePermissionService permissionService;
    private final ObjectMapper objectMapper;

    public record StepResult(double score, boolean passed, String actual, String details) {
    }

    public StepResult evaluate(String stepType, String query, String expectedPayload, String stepConfig,
                               KnowledgePermissionService.Viewer viewer) {
        String type = stepType == null ? "ANSWER" : stepType.toUpperCase(Locale.ROOT);
        Map<String, Object> expected = parse(expectedPayload);
        Map<String, Object> config = parse(stepConfig);
        return switch (type) {
            case "RETRIEVAL" -> retrieval(query, expected, config, viewer);
            case "RERANK" -> rerank(query, expected, config, viewer);
            case "REWRITE" -> rewrite(query, expected, config);
            default -> throw new BusinessException(400, "步骤评测类型不支持：" + stepType);
        };
    }

    private StepResult retrieval(String query, Map<String, Object> expected, Map<String, Object> config,
                                 KnowledgePermissionService.Viewer viewer) {
        Long kbId = longValue(config.get("knowledgeBaseId"));
        if (kbId == null) throw new BusinessException(400, "检索步骤缺少 knowledgeBaseId");
        permissionService.requireView(kbId, viewer);
        int topK = intValue(config.get("topK"), 10);
        double threshold = doubleValue(config.get("threshold"), 0.0);
        String mode = str(config.get("mode"));
        Map<String, Object> retrievalConfig = mode == null ? Map.of() : Map.of("mode", mode);
        HybridRetrievalService.Result result = retrievalService.search(
                TenantContext.getTenantId(), query, kbId, topK, threshold, retrievalConfig,
                viewer.userId(), permissionService.tenantWide(viewer));
        List<Long> actualIds = chunkIds(result.hits());
        List<Long> expectedIds = ids(expected.get("chunk_ids"), expected.get("expectedChunkIds"));
        if (expectedIds.isEmpty()) throw new BusinessException(400, "检索步骤缺少 expected chunk_ids");
        Set<Long> actualSet = new LinkedHashSet<>(actualIds);
        long matched = expectedIds.stream().filter(actualSet::contains).count();
        double score = (double) matched / expectedIds.size();
        double passThreshold = doubleValue(config.get("passThreshold"), 0.8);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("mode", result.mode());
        details.put("expectedChunkIds", expectedIds);
        details.put("actualChunkIds", actualIds);
        details.put("recall", score);
        details.put("vectorHits", result.vectorHits());
        details.put("keywordHits", result.keywordHits());
        details.put("embedMs", result.embedMs());
        details.put("searchMs", result.searchMs());
        return new StepResult(score, score >= passThreshold, json(actualIds), json(details));
    }

    private StepResult rerank(String query, Map<String, Object> expected, Map<String, Object> config,
                              KnowledgePermissionService.Viewer viewer) {
        Long kbId = longValue(config.get("knowledgeBaseId"));
        if (kbId == null) throw new BusinessException(400, "重排步骤缺少 knowledgeBaseId");
        permissionService.requireView(kbId, viewer);
        int topK = intValue(config.get("topK"), 10);
        double threshold = doubleValue(config.get("threshold"), 0.0);
        String mode = str(config.get("mode"));
        Map<String, Object> retrievalConfig = mode == null ? Map.of() : Map.of("mode", mode);
        HybridRetrievalService.Result result = retrievalService.search(
                TenantContext.getTenantId(), query, kbId, topK, threshold, retrievalConfig,
                viewer.userId(), permissionService.tenantWide(viewer));
        List<Long> before = chunkIds(result.hits());
        List<Map<String, Object>> reranked = rerankService.rerank(query, result.hits(),
                intValue(config.get("rerankTopN"), Math.min(5, Math.max(1, topK))));
        List<Long> after = chunkIds(reranked);
        List<Long> expectedOrder = ids(expected.get("ordered_chunk_ids"), expected.get("chunk_ids"));
        if (expectedOrder.isEmpty()) throw new BusinessException(400, "重排步骤缺少 expected ordered_chunk_ids");
        int rank = -1;
        for (int i = 0; i < after.size(); i++) {
            if (after.get(i).equals(expectedOrder.get(0))) { rank = i + 1; break; }
        }
        long matched = expectedOrder.stream().filter(after::contains).count();
        double score = rank > 0 ? 1.0 / rank : 0.0;
        double passThreshold = doubleValue(config.get("passThreshold"), 0.5);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("expectedOrder", expectedOrder);
        details.put("beforeChunkIds", before);
        details.put("afterChunkIds", after);
        details.put("expectedFirstRank", rank);
        details.put("matched", matched);
        details.put("rerankEnabled", rerankService.enabled());
        return new StepResult(score, score >= passThreshold, json(after), json(details));
    }

    private StepResult rewrite(String query, Map<String, Object> expected, Map<String, Object> config) {
        String model = str(config.get("model"));
        String instructions = str(config.get("instructions"));
        String rewritten = rewriteService.rewrite(query, model, instructions);
        String expectedText = str(expected.get("rewrite"));
        if (expectedText == null) expectedText = str(expected.get("expectedRewrite"));
        List<String> mustKeep = strings(expected.get("must_keep"));
        String normalizedActual = normalize(rewritten);
        boolean exact = expectedText != null && normalizedActual.equals(normalize(expectedText));
        boolean kept = mustKeep.stream().allMatch(value -> normalizedActual.contains(normalize(value)));
        double score = expectedText == null ? (kept ? 1.0 : 0.0) : (exact ? 1.0 : kept ? 0.5 : 0.0);
        double passThreshold = doubleValue(config.get("passThreshold"), 0.8);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rewritten", rewritten);
        details.put("expectedRewrite", expectedText);
        details.put("mustKeep", mustKeep);
        details.put("exact", exact);
        details.put("keptEntities", kept);
        return new StepResult(score, score >= passThreshold, rewritten, json(details));
    }

    private List<Long> chunkIds(List<Map<String, Object>> hits) {
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> hit : hits) {
            try {
                Object metadata = hit.get("metadata");
                if (metadata instanceof String text) {
                    Long id = longValue(objectMapper.readValue(text, Map.class).get("chunk_id"));
                    if (id != null) ids.add(id);
                }
            } catch (Exception ignored) {
            }
        }
        return ids;
    }

    private List<Long> ids(Object first, Object second) {
        List<Long> result = new ArrayList<>();
        for (Object source : new Object[]{first, second}) {
            if (source instanceof List<?> list) {
                for (Object item : list) {
                    Long value = longValue(item);
                    if (value != null) result.add(value);
                }
                if (!result.isEmpty()) break;
            }
        }
        return result;
    }

    private List<String> strings(Object source) {
        if (!(source instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}]+", "");
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new BusinessException(400, "步骤 payload/config 不是合法 JSON");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value instanceof Number n) return n.longValue();
        try { return value == null ? null : Long.parseLong(String.valueOf(value)); }
        catch (Exception e) { return null; }
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
        catch (Exception e) { return fallback; }
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number n) return n.doubleValue();
        try { return value == null ? fallback : Double.parseDouble(String.valueOf(value)); }
        catch (Exception e) { return fallback; }
    }
}
