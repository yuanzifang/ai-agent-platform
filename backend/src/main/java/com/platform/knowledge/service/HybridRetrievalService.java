package com.platform.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.exception.BusinessException;
import com.platform.knowledge.mapper.KnowledgeChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索：向量召回 + MySQL ngram 全文召回，RRF 融合后按 topK 返回。
 * 向量服务不可用时自动退回关键字路；retrievalConfig.mode=vector/keyword/hybrid 可覆盖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetrievalService {

    private final EmbeddingService embeddingService;
    private final VectorRepository vectorRepository;
    private final KnowledgeChunkMapper chunkMapper;
    private final ObjectMapper objectMapper;

    @Value("${retrieval.hybrid.enabled:true}")
    private boolean hybridEnabled;

    @Value("${retrieval.hybrid.keyword-weight:0.35}")
    private double defaultKeywordWeight;

    public record Result(List<Map<String, Object>> hits, int vectorHits, int keywordHits,
                         long embedMs, long searchMs, String mode) {
    }

    private static final class Candidate {
        private String content;
        private String metadata;
        private Double vectorScore;
        private Double keywordScore;
        private double score;
    }

    public Result search(Long tenantId, String query, Long kbId, int topK, double threshold) {
        return search(tenantId, query, kbId, topK, threshold, Map.of());
    }

    public Result search(Long tenantId, String query, Long kbId, int topK, double threshold,
                         Map<String, Object> retrievalConfig) {
        int limit = Math.max(1, Math.min(topK, 20));
        int candidates = Math.max(10, Math.min(limit * 3, 30));
        String mode = str(retrievalConfig.get("mode"));
        if (mode == null || mode.isBlank()) mode = hybridEnabled ? "hybrid" : "vector";
        mode = mode.toLowerCase();
        if (!List.of("vector", "keyword", "hybrid").contains(mode)) mode = "hybrid";
        double keywordWeight = toDouble(retrievalConfig.get("keywordWeight"), defaultKeywordWeight);
        keywordWeight = Math.max(0, Math.min(keywordWeight, 1));
        double vectorWeight = 1 - keywordWeight;
        String q = query == null ? "" : query.trim();
        if (q.isBlank()) return new Result(List.of(), 0, 0, 0, 0, mode);
        String keywordQuery = q.length() > 120 ? q.substring(0, 120) : q;

        long embedMs = 0;
        long searchMs = 0;
        List<Map<String, Object>> vectorHits = new ArrayList<>();
        if (!"keyword".equals(mode)) {
            long start = System.currentTimeMillis();
            try {
                float[] vector = embeddingService.embedOne(q);
                embedMs = System.currentTimeMillis() - start;
                long searchStart = System.currentTimeMillis();
                vectorHits = vectorRepository.search(tenantId, vector, kbId, candidates, threshold);
                searchMs = System.currentTimeMillis() - searchStart;
            } catch (Exception e) {
                log.warn("向量检索失败，尝试关键字兜底 kb={}：{}", kbId, e.getMessage());
                if (!"hybrid".equals(mode)) {
                    throw new BusinessException(502, "向量检索失败：" + e.getMessage());
                }
                mode = "keyword";
            }
        }

        List<Map<String, Object>> keywordHits = new ArrayList<>();
        if (!"vector".equals(mode)) {
            long start = System.currentTimeMillis();
            try {
                keywordHits = chunkMapper.searchFulltext(kbId, keywordQuery, candidates);
            } catch (Exception e) {
                log.debug("全文检索不可用，尝试 LIKE 兜底 kb={}：{}", kbId, e.getMessage());
                try {
                    keywordHits = chunkMapper.searchLike(kbId, keywordQuery, candidates);
                } catch (Exception ex) {
                    log.warn("关键字检索失败 kb={}：{}", kbId, ex.getMessage());
                }
            }
            searchMs += System.currentTimeMillis() - start;
        }

        Map<String, Candidate> merged = new LinkedHashMap<>();
        Map<String, Integer> vectorRank = new HashMap<>();
        Map<String, Integer> keywordRank = new HashMap<>();
        for (int i = 0; i < vectorHits.size(); i++) {
            Map<String, Object> hit = vectorHits.get(i);
            String content = str(hit.get("content"));
            String key = chunkIdOf(hit.get("metadata"), "v" + i);
            Candidate c = merged.computeIfAbsent(key, k -> new Candidate());
            c.content = content;
            c.metadata = str(hit.get("metadata"));
            c.vectorScore = toDouble(hit.get("score"), 0);
            vectorRank.put(key, i + 1);
        }
        for (int i = 0; i < keywordHits.size(); i++) {
            Map<String, Object> hit = keywordHits.get(i);
            String key = String.valueOf(hit.get("id"));
            Candidate c = merged.computeIfAbsent(key, k -> new Candidate());
            c.content = str(hit.get("content"));
            if (c.metadata == null) c.metadata = keywordMetadata(kbId, hit);
            c.keywordScore = toDouble(hit.get("score"), 0);
            keywordRank.put(key, i + 1);
        }

        if ("vector".equals(mode)) {
            for (var e : merged.entrySet()) e.getValue().score = e.getValue().vectorScore == null
                    ? 0 : e.getValue().vectorScore;
        } else if ("keyword".equals(mode)) {
            normalizeKeyword(merged);
        } else {
            int rrfK = 60;
            for (var e : merged.entrySet()) {
                Candidate c = e.getValue();
                double score = 0;
                Integer vr = vectorRank.get(e.getKey());
                Integer kr = keywordRank.get(e.getKey());
                if (vr != null) score += vectorWeight / (rrfK + vr);
                if (kr != null) score += keywordWeight / (rrfK + kr);
                c.score = score;
            }
            double max = merged.values().stream().mapToDouble(c -> c.score).max().orElse(0);
            if (max > 0) {
                for (Candidate c : merged.values()) c.score = c.score / max;
            }
        }

        List<Map<String, Object>> result = merged.values().stream()
                .filter(c -> c.content != null && !c.content.isBlank())
                .sorted(Comparator.comparingDouble((Candidate c) -> c.score).reversed())
                .limit(limit)
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("content", c.content);
                    m.put("score", c.score);
                    m.put("metadata", c.metadata);
                    return m;
                }).toList();
        log.debug("混合检索 kb={} mode={} vector={} keyword={} merged={}",
                kbId, mode, vectorHits.size(), keywordHits.size(), result.size());
        return new Result(result, vectorHits.size(), keywordHits.size(), embedMs, searchMs, mode);
    }

    private void normalizeKeyword(Map<String, Candidate> merged) {
        double max = merged.values().stream()
                .mapToDouble(c -> c.keywordScore == null ? 0 : c.keywordScore).max().orElse(0);
        for (Candidate c : merged.values()) {
            c.score = max <= 0 || c.keywordScore == null ? 0 : c.keywordScore / max;
        }
    }

    private String chunkIdOf(Object metadata, String fallback) {
        if (metadata == null) return fallback;
        try {
            Map<?, ?> m = objectMapper.readValue(String.valueOf(metadata), Map.class);
            Object id = m.get("chunk_id");
            return id == null ? fallback : String.valueOf(id);
        } catch (Exception e) {
            return fallback;
        }
    }

    private String keywordMetadata(Long kbId, Map<String, Object> hit) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("knowledge_base_id", kbId);
        meta.put("document_id", hit.get("document_id"));
        meta.put("chunk_id", hit.get("id"));
        meta.put("chunk_index", hit.get("chunk_index"));
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            return "{}";
        }
    }

    private double toDouble(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) { }
        }
        return def;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
