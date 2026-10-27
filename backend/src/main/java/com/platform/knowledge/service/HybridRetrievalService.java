package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.exception.BusinessException;
import com.platform.knowledge.entity.KnowledgeChunk;
import com.platform.knowledge.entity.KnowledgeParent;
import com.platform.knowledge.mapper.KnowledgeChunkMapper;
import com.platform.knowledge.mapper.KnowledgeParentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 混合检索：向量召回 + MySQL ngram 全文召回，RRF 融合后按 topK 返回。
 * 向量服务不可用时自动退回关键字路；retrievalConfig.mode=vector/keyword/hybrid 可覆盖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetrievalService {

    private final EmbeddingService embeddingService;
    private final VectorStore vectorRepository;
    private final KnowledgeChunkMapper chunkMapper;
    private final com.platform.knowledge.mapper.KnowledgeBaseMapper kbMapper;
    private final ObjectMapper objectMapper;

    @Value("${retrieval.hybrid.enabled:true}")
    private boolean hybridEnabled;

    @Value("${retrieval.hybrid.keyword-weight:0.35}")
    private double defaultKeywordWeight;

    @Autowired(required = false)
    private RerankService rerankService;

    @Autowired(required = false)
    private MysqlKeywordSearchStore mysqlKeywordSearchStore;
    @Autowired(required = false)
    private ElasticsearchKeywordSearchStore elasticsearchKeywordSearchStore;

    @Autowired(required = false)
    private KnowledgeParentMapper parentMapper;

    public record Result(List<Map<String, Object>> hits, int vectorHits, int keywordHits,
                         long embedMs, long searchMs, String mode) {
    }

    private static final class Candidate {
        private String content;
        private String metadata;
        private Double vectorScore;
        private Double keywordScore;
        private double relevanceScore;
        private double score;
    }

    public Result search(Long tenantId, String query, Long kbId, int topK, double threshold) {
        return search(tenantId, query, kbId, topK, threshold, Map.of());
    }

    public Result search(Long tenantId, String query, Long kbId, int topK, double threshold,
                         Map<String, Object> retrievalConfig) {
        return search(tenantId, query, kbId, topK, threshold, retrievalConfig, null, true);
    }

    /** viewer 不为空时把 chunk ACL 下推到向量/关键字 SQL。 */
    public Result search(Long tenantId, String query, Long kbId, int topK, double threshold,
                         Map<String, Object> retrievalConfig, Long userId, boolean tenantWide) {
        return search(tenantId, query, kbId, topK, threshold, retrievalConfig,
                userId, tenantWide, null, null);
    }

    /** 向量与关键字都按内容有效期区间重叠过滤；有效期在 SQL 的 LIMIT 前生效。 */
    public Result search(Long tenantId, String query, Long kbId, int topK, double threshold,
                         Map<String, Object> retrievalConfig, Long userId, boolean tenantWide,
                         Instant effectiveAfter, Instant effectiveBefore) {
        Map<String, Object> config = retrievalConfig == null ? Map.of() : retrievalConfig;
        int limit = Math.max(1, Math.min(topK, 20));
        int candidates = Math.max(10, Math.min(limit * 3, 30));
        String mode = str(config.get("mode"));
        if (mode == null || mode.isBlank()) mode = hybridEnabled ? "hybrid" : "vector";
        mode = mode.toLowerCase();
        if (!List.of("vector", "keyword", "hybrid").contains(mode)) {
            mode = hybridEnabled ? "hybrid" : "vector";
        }
        double keywordWeight = toDouble(config.get("keywordWeight"), defaultKeywordWeight);
        keywordWeight = Math.max(0, Math.min(keywordWeight, 1));
        double vectorWeight = 1 - keywordWeight;
        boolean hasValidity = effectiveAfter != null || effectiveBefore != null;
        LocalDateTime validityAfter = effectiveAfter == null ? null
                : LocalDateTime.ofInstant(effectiveAfter, ZoneId.systemDefault());
        LocalDateTime validityBefore = effectiveBefore == null ? null
                : LocalDateTime.ofInstant(effectiveBefore, ZoneId.systemDefault());
        String q = query == null ? "" : query.trim();
        if (q.isBlank()) return new Result(List.of(), 0, 0, 0, 0, mode);
        String keywordQuery = q.length() > 120 ? q.substring(0, 120) : q;

        long embedMs = 0;
        long searchMs = 0;
        List<Map<String, Object>> vectorHits = new ArrayList<>();
        if (!"keyword".equals(mode)) {
            long start = System.currentTimeMillis();
            try {
                com.platform.knowledge.entity.KnowledgeBase kb = kbMapper.selectById(kbId);
                String embeddingModel = kb == null || kb.getEmbeddingModel() == null
                        || kb.getEmbeddingModel().isBlank()
                        ? embeddingService.model() : kb.getEmbeddingModel();
                int dimensions = embeddingService.dimension(embeddingModel);
                float[] vector = embeddingService.embedQuery(q, embeddingModel);
                embedMs = System.currentTimeMillis() - start;
                long searchStart = System.currentTimeMillis();
                if (userId == null) {
                    vectorHits = hasValidity
                            ? vectorRepository.search(tenantId, vector, kbId, candidates, threshold,
                                    dimensions, null, true, effectiveAfter, effectiveBefore)
                            : vectorRepository.search(tenantId, vector, kbId, candidates, threshold, dimensions);
                } else {
                    vectorHits = hasValidity
                            ? vectorRepository.search(tenantId, vector, kbId, candidates, threshold,
                                    dimensions, userId, tenantWide, effectiveAfter, effectiveBefore)
                            : vectorRepository.search(tenantId, vector, kbId, candidates, threshold,
                                    dimensions, userId, tenantWide);
                }
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
                KeywordSearchStore store = keywordStore(kbId);
                keywordHits = store != null
                        ? store.search(tenantId, kbId, keywordQuery, candidates, userId,
                                tenantWide, effectiveAfter, effectiveBefore)
                        : hasValidity
                        ? chunkMapper.searchFulltextByValidity(kbId, keywordQuery, candidates,
                                validityAfter, validityBefore, userId, tenantWide)
                        : userId == null
                        ? chunkMapper.searchFulltext(kbId, keywordQuery, candidates)
                        : searchFulltextAclHydrated(kbId, keywordQuery, candidates, userId, tenantWide);
            } catch (Exception e) {
                log.debug("全文检索不可用，尝试 LIKE 兜底 kb={}：{}", kbId, e.getMessage());
                try {
                    keywordHits = hasValidity
                            ? chunkMapper.searchLikeByValidity(kbId, keywordQuery, candidates,
                                    validityAfter, validityBefore, userId, tenantWide)
                            : userId == null
                            ? chunkMapper.searchLike(kbId, keywordQuery, candidates)
                            : chunkMapper.searchLikeAcl(kbId, keywordQuery, candidates, userId, tenantWide);
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
            if (!matchesFilters(hit.get("metadata"), retrievalConfig)) continue;
            String content = str(hit.get("content"));
            String key = chunkIdOf(hit.get("metadata"), "v" + i);
            Candidate c = merged.computeIfAbsent(key, k -> new Candidate());
            c.content = content;
            c.metadata = str(hit.get("metadata"));
            c.vectorScore = toDouble(hit.get("score"), 0);
            c.relevanceScore = relevance(hit.get("metadata"));
            vectorRank.put(key, i + 1);
        }
        for (int i = 0; i < keywordHits.size(); i++) {
            Map<String, Object> hit = keywordHits.get(i);
            if (!matchesFilters(keywordMetadata(kbId, hit), retrievalConfig)) continue;
            String key = String.valueOf(hit.get("id"));
            Candidate c = merged.computeIfAbsent(key, k -> new Candidate());
            if (c.content == null || c.content.isBlank()) c.content = str(hit.get("content"));
            if (c.metadata == null) c.metadata = keywordMetadata(kbId, hit);
            c.keywordScore = toDouble(hit.get("score"), 0);
            c.relevanceScore = relevance(c.metadata);
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

        List<Map<String, Object>> result = new ArrayList<>(merged.values().stream()
                .filter(c -> c.content != null && !c.content.isBlank())
                .sorted(Comparator.comparingDouble((Candidate c) -> c.score).reversed()
                        .thenComparing(Comparator.comparingDouble(
                                (Candidate c) -> c.relevanceScore).reversed()))
                .limit(limit)
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("content", c.content);
                    m.put("score", c.score);
                    m.put("metadata", c.metadata);
                    return m;
                }).toList());
        enrichNeighbors(kbId, result);
        result = dedupeByParent(result);
        boolean rerank = rerankService != null && rerankService.enabled()
                && !"false".equalsIgnoreCase(String.valueOf(config.getOrDefault("rerank", "true")));
        if (rerank) {
            result = new ArrayList<>(rerankService.rerank(q, result, limit));
        }
        log.debug("混合检索 kb={} mode={} vector={} keyword={} merged={} rerank={}",
                kbId, mode, vectorHits.size(), keywordHits.size(), result.size(), rerank);
        return new Result(result, vectorHits.size(), keywordHits.size(), embedMs, searchMs, mode);
    }

    private KeywordSearchStore keywordStore(Long kbId) {
        try {
            com.platform.knowledge.entity.KnowledgeBase kb = kbMapper.selectById(kbId);
            if (kb != null && "ES".equalsIgnoreCase(kb.getSearchBackend())
                    && elasticsearchKeywordSearchStore != null) {
                return elasticsearchKeywordSearchStore;
            }
        } catch (Exception ignored) {
        }
        return mysqlKeywordSearchStore;
    }

    /** 先由 MySQL 只取命中 ID/score，再按 ID 回表取详情，避免全文查询搬运大量 content。 */
    private List<Map<String, Object>> searchFulltextAclHydrated(Long kbId, String query, int limit,
                                                                 Long userId, boolean tenantWide) {
        List<Map<String, Object>> scored = chunkMapper.searchFulltextAclIds(
                kbId, query, limit, userId, tenantWide);
        if (scored == null) {
            // 兼容旧 mapper/测试替身；生产路径返回列表，即使是空列表。
            return chunkMapper.searchFulltextAcl(kbId, query, limit, userId, tenantWide);
        }
        if (scored.isEmpty()) return List.of();
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Map<String, Object> row : scored) {
            Long id = longValue(row.get("id"));
            if (id != null) ids.add(id);
        }
        if (ids.isEmpty()) return List.of();
        List<KnowledgeChunk> chunks = chunkMapper.selectBatchIds(ids);
        if (chunks == null || chunks.isEmpty()) return List.of();
        Map<Long, KnowledgeChunk> byId = new HashMap<>();
        for (KnowledgeChunk chunk : chunks) {
            if (chunk != null && chunk.getId() != null) byId.put(chunk.getId(), chunk);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : scored) {
            Long id = longValue(row.get("id"));
            KnowledgeChunk chunk = id == null ? null : byId.get(id);
            if (chunk == null) continue;
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("id", chunk.getId());
            hit.put("document_id", chunk.getDocumentId());
            hit.put("chunk_index", chunk.getChunkIndex());
            hit.put("parent_index", chunk.getParentIndex());
            hit.put("document_type", chunk.getDocumentType());
            hit.put("section_type", chunk.getSectionType());
            hit.put("chunk_level", chunk.getChunkLevel());
            hit.put("parent_key", chunk.getParentKey());
            hit.put("domain_keywords", chunk.getDomainKeywords());
            hit.put("relevance_score", chunk.getRelevanceScore());
            hit.put("regulatory_category", chunk.getRegulatoryCategory());
            hit.put("content", chunk.getContent());
            hit.put("page_from", chunk.getPageFrom());
            hit.put("page_to", chunk.getPageTo());
            hit.put("score", row.get("score"));
            result.add(hit);
        }
        return result;
    }

    /** 同一父块下的多个子块命中合并为一条，保留多个分数，减少重复父块注入。 */
    private List<Map<String, Object>> dedupeByParent(List<Map<String, Object>> hits) {
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> hit : hits) {
            HitMeta meta = readHitMeta(hit.get("metadata"));
            Long parentIndex = longValue(hit.get("parentIndex"));
            Long chunkId = meta.chunkId();
            String key;
            if (meta.docId() != null && parentIndex != null) {
                key = "p:" + meta.docId() + ":" + parentIndex;
            } else if (chunkId != null) {
                key = "c:" + chunkId;
            } else {
                key = "h:" + meta.docId() + ":" + String.valueOf(hit.get("content")).hashCode();
            }
            Map<String, Object> existing = unique.get(key);
            if (existing == null) {
                unique.put(key, new LinkedHashMap<>(hit));
                continue;
            }
            double previous = toDouble(existing.get("score"), 0);
            double current = toDouble(hit.get("score"), 0);
            existing.put("score", Math.max(previous, current));
            @SuppressWarnings("unchecked")
            List<Object> scores = existing.get("mergedScores") instanceof List<?>
                    ? (List<Object>) existing.get("mergedScores") : new ArrayList<>();
            if (!scores.contains(previous)) scores.add(previous);
            if (!scores.contains(current)) scores.add(current);
            existing.put("mergedScores", scores);
            existing.put("mergedHitCount", scores.size());
        }
        return new ArrayList<>(unique.values());
    }

    /** 父块注入：优先使用入库时保存的 parent_content；历史数据回退到相邻 chunk 合并。 */
    private void enrichNeighbors(Long kbId, List<Map<String, Object>> hits) {
        if (hits == null || hits.isEmpty()) return;

        // 先批量读取父块，再一次性读取所有缺失命中需要的相邻分段，避免按 hit 逐条查询。
        List<HitMeta> metas = new ArrayList<>(hits.size());
        Set<Long> chunkIds = new LinkedHashSet<>();
        for (Map<String, Object> hit : hits) {
            HitMeta meta = readHitMeta(hit.get("metadata"));
            metas.add(meta);
            if (meta.chunkId() != null) chunkIds.add(meta.chunkId());
        }
        Map<Long, KnowledgeChunk> storedById = new HashMap<>();
        if (!chunkIds.isEmpty()) {
            try {
                List<KnowledgeChunk> stored = chunkMapper.selectBatchIds(chunkIds);
                if (stored != null) {
                    for (KnowledgeChunk chunk : stored) {
                        if (chunk != null && chunk.getId() != null) storedById.put(chunk.getId(), chunk);
                    }
                }
            } catch (Exception e) {
                log.debug("父块批量读取失败：{}", e.getMessage());
            }
        }

        List<NeighborNeed> needs = new ArrayList<>();
        List<ParentNeed> parentNeeds = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            HitMeta meta = metas.get(i);
            KnowledgeChunk stored = meta.chunkId() == null ? null : storedById.get(meta.chunkId());
            Integer parentIndex = stored != null && stored.getParentIndex() != null
                    ? stored.getParentIndex() : meta.parentIndex();
            if (stored != null && stored.getParentContent() != null
                    && !stored.getParentContent().isBlank()) {
                hits.get(i).put("parentContent", stored.getParentContent());
                hits.get(i).put("parentIndex", parentIndex);
                continue;
            }
            if (meta.docId() != null && parentIndex != null) {
                parentNeeds.add(new ParentNeed(i, meta.docId(), parentIndex));
            } else if (meta.docId() != null && meta.chunkIndex() != null) {
                needs.add(new NeighborNeed(i, meta.docId(), meta.chunkIndex()));
            }
        }
        if (!parentNeeds.isEmpty() && parentMapper != null) {
            Set<Long> docIds = new LinkedHashSet<>();
            Set<Integer> indexes = new LinkedHashSet<>();
            for (ParentNeed need : parentNeeds) {
                docIds.add(need.docId());
                indexes.add(need.parentIndex());
            }
            Map<String, String> parentByKey = new HashMap<>();
            try {
                List<KnowledgeParent> parents = parentMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeParent>()
                                .in(KnowledgeParent::getDocumentId, docIds)
                                .in(KnowledgeParent::getParentIndex, indexes));
                if (parents != null) {
                    for (KnowledgeParent parent : parents) {
                        parentByKey.put(parent.getDocumentId() + ":" + parent.getParentIndex(),
                                parent.getContent());
                    }
                }
            } catch (Exception e) {
                log.debug("父块表批量读取失败：{}", e.getMessage());
            }
            for (ParentNeed need : parentNeeds) {
                String content = parentByKey.get(need.docId() + ":" + need.parentIndex());
                if (content != null && !content.isBlank()) {
                    hits.get(need.hitIndex()).put("parentContent", content);
                    hits.get(need.hitIndex()).put("parentIndex", need.parentIndex());
                } else {
                    HitMeta meta = metas.get(need.hitIndex());
                    if (meta.chunkIndex() != null) {
                        needs.add(new NeighborNeed(need.hitIndex(), need.docId(), meta.chunkIndex()));
                    }
                }
            }
        } else {
            for (ParentNeed need : parentNeeds) {
                HitMeta meta = metas.get(need.hitIndex());
                if (meta.chunkIndex() != null) {
                    needs.add(new NeighborNeed(need.hitIndex(), need.docId(), meta.chunkIndex()));
                }
            }
        }
        if (needs.isEmpty()) return;

        List<KnowledgeChunk> neighbors;
        try {
            LambdaQueryWrapper<KnowledgeChunk> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(KnowledgeChunk::getKnowledgeBaseId, kbId);
            wrapper.and(scope -> {
                boolean first = true;
                for (NeighborNeed need : needs) {
                    if (!first) scope.or();
                    scope.nested(cond -> cond.eq(KnowledgeChunk::getDocumentId, need.docId())
                            .between(KnowledgeChunk::getChunkIndex,
                                    Math.max(0, need.chunkIndex() - 1), need.chunkIndex() + 1));
                    first = false;
                }
            });
            wrapper.orderByAsc(KnowledgeChunk::getDocumentId)
                    .orderByAsc(KnowledgeChunk::getChunkIndex);
            neighbors = chunkMapper.selectList(wrapper);
        } catch (Exception e) {
            log.debug("相邻分段批量读取失败：{}", e.getMessage());
            return;
        }
        if (neighbors == null || neighbors.isEmpty()) return;

        Map<Long, Map<Integer, KnowledgeChunk>> byDocument = new HashMap<>();
        for (KnowledgeChunk chunk : neighbors) {
            if (chunk == null || chunk.getDocumentId() == null || chunk.getChunkIndex() == null) continue;
            byDocument.computeIfAbsent(chunk.getDocumentId(), k -> new HashMap<>())
                    .put(chunk.getChunkIndex(), chunk);
        }
        for (NeighborNeed need : needs) {
            Map<Integer, KnowledgeChunk> docChunks = byDocument.get(need.docId());
            if (docChunks == null) continue;
            List<KnowledgeChunk> range = new ArrayList<>(3);
            for (int idx = Math.max(0, need.chunkIndex() - 1); idx <= need.chunkIndex() + 1; idx++) {
                KnowledgeChunk chunk = docChunks.get(idx);
                if (chunk != null) range.add(chunk);
            }
            if (range.size() <= 1) continue;
            StringBuilder parent = new StringBuilder();
            for (KnowledgeChunk chunk : range) {
                if (parent.length() > 0) parent.append('\n');
                parent.append(chunk.getContent());
            }
            hits.get(need.hitIndex()).put("parentContent", parent.toString());
        }
    }

    private HitMeta readHitMeta(Object metadata) {
        if (!(metadata instanceof String text) || text.isBlank()) {
            return new HitMeta(null, null, null, null);
        }
        try {
            Map<?, ?> meta = objectMapper.readValue(text, Map.class);
            return new HitMeta(longValue(meta.get("chunk_id")), longValue(meta.get("document_id")),
                    intValue(meta.get("chunk_index")), intValue(meta.get("parent_index")));
        } catch (Exception e) {
            return new HitMeta(null, null, null, null);
        }
    }

    private Long longValue(Object value) {
        if (value == null) return null;
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer intValue(Object value) {
        if (value == null) return null;
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record HitMeta(Long chunkId, Long docId, Integer chunkIndex, Integer parentIndex) {
    }

    private record NeighborNeed(int hitIndex, Long docId, int chunkIndex) {
    }

    private record ParentNeed(int hitIndex, Long docId, int parentIndex) {
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
        if (hit.get("parent_index") != null) meta.put("parent_index", hit.get("parent_index"));
        if (hit.get("document_type") != null) meta.put("document_type", hit.get("document_type"));
        if (hit.get("section_type") != null) meta.put("section_type", hit.get("section_type"));
        if (hit.get("chunk_level") != null) meta.put("chunk_level", hit.get("chunk_level"));
        if (hit.get("parent_key") != null) meta.put("parent_key", hit.get("parent_key"));
        if (hit.get("domain_keywords") != null) meta.put("domain_keywords", hit.get("domain_keywords"));
        if (hit.get("relevance_score") != null) meta.put("relevance_score", hit.get("relevance_score"));
        if (hit.get("regulatory_category") != null) meta.put("regulatory_category", hit.get("regulatory_category"));
        if (hit.get("page_from") != null) meta.put("page_from", hit.get("page_from"));
        if (hit.get("page_to") != null) meta.put("page_to", hit.get("page_to"));
        if (hit.get("effective_from") != null) meta.put("effective_from", hit.get("effective_from"));
        if (hit.get("effective_to") != null) meta.put("effective_to", hit.get("effective_to"));
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** PgVector jsonb 经 JDBC 返回 PGobject，统一转成 JSON 字符串后再解析。 */
    private String metadataText(Object metadata) {
        if (metadata == null) return null;
        return metadata instanceof String text ? text : String.valueOf(metadata);
    }

    /** 语义/规则过滤：文档类型、章节、监管类别、块层级、最低静态相关性。 */
    private double relevance(Object metadata) {
        String text = metadataText(metadata);
        if (text == null || text.isBlank()) return 0;
        try {
            Map<?, ?> meta = objectMapper.readValue(text, Map.class);
            return toDouble(meta.get("relevance_score"), 0);
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean matchesFilters(Object metadata, Map<String, Object> config) {
        if (config == null || config.isEmpty()) return true;
        String text = metadataText(metadata);
        Map<?, ?> meta;
        try {
            meta = text != null && !text.isBlank()
                    ? objectMapper.readValue(text, Map.class) : Map.of();
        } catch (Exception e) {
            return false;
        }
        String docType = str(config.get("documentType"));
        if (docType != null && !docType.isBlank()
                && !docType.equalsIgnoreCase(str(meta.get("document_type")))) return false;
        String section = str(config.get("sectionType"));
        if (section != null && !section.isBlank()
                && !section.equalsIgnoreCase(str(meta.get("section_type")))) return false;
        String regulatory = str(config.get("regulatoryCategory"));
        if (regulatory != null && !regulatory.isBlank()
                && !regulatory.equalsIgnoreCase(str(meta.get("regulatory_category")))) return false;
        Object level = config.get("chunkLevel");
        if (level != null && !String.valueOf(level).isBlank()) {
            Object actual = meta.get("chunk_level");
            if (actual == null || intValue(actual) == null
                    || !String.valueOf(level).equals(String.valueOf(intValue(actual)))) return false;
        }
        Object minRelevance = config.get("minRelevance");
        if (minRelevance != null) {
            Object actual = meta.get("relevance_score");
            if (actual == null || toDouble(actual, 0) < toDouble(minRelevance, 0)) return false;
        }
        return true;
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
