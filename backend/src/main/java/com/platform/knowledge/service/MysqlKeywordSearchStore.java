package com.platform.knowledge.service;

import com.platform.knowledge.entity.KnowledgeChunk;
import com.platform.knowledge.mapper.KnowledgeChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 默认 MySQL ngram 全文实现。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MysqlKeywordSearchStore implements KeywordSearchStore {

    private final KnowledgeChunkMapper chunkMapper;

    @Override
    public String provider() {
        return "mysql";
    }

    @Override
    public List<Map<String, Object>> search(Long tenantId, Long knowledgeBaseId, String query, int limit,
                                            Long userId, boolean tenantWide,
                                            Instant effectiveAfter, Instant effectiveBefore) {
        try {
            if (effectiveAfter != null || effectiveBefore != null) {
                LocalDateTime after = effectiveAfter == null ? null
                        : LocalDateTime.ofInstant(effectiveAfter, ZoneId.systemDefault());
                LocalDateTime before = effectiveBefore == null ? null
                        : LocalDateTime.ofInstant(effectiveBefore, ZoneId.systemDefault());
                return chunkMapper.searchFulltextByValidity(knowledgeBaseId, query, limit,
                        after, before, userId, tenantWide);
            }
            if (userId == null) {
                return chunkMapper.searchFulltext(knowledgeBaseId, query, limit);
            }
            List<Map<String, Object>> scored = chunkMapper.searchFulltextAclIds(
                    knowledgeBaseId, query, limit, userId, tenantWide);
            if (scored == null) {
                return chunkMapper.searchFulltextAcl(knowledgeBaseId, query, limit, userId, tenantWide);
            }
            return hydrate(scored);
        } catch (Exception e) {
            log.debug("MySQL 全文检索失败，LIKE 回退 kb={} err={}", knowledgeBaseId, e.getMessage());
            return userId == null
                    ? chunkMapper.searchLike(knowledgeBaseId, query, limit)
                    : chunkMapper.searchLikeAcl(knowledgeBaseId, query, limit, userId, tenantWide);
        }
    }

    private List<Map<String, Object>> hydrate(List<Map<String, Object>> scored) {
        if (scored.isEmpty()) return List.of();
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Map<String, Object> row : scored) {
            Long id = longValue(row.get("id"));
            if (id != null) ids.add(id);
        }
        if (ids.isEmpty()) return List.of();
        List<KnowledgeChunk> chunks = chunkMapper.selectBatchIds(ids);
        Map<Long, KnowledgeChunk> byId = new HashMap<>();
        if (chunks != null) for (KnowledgeChunk chunk : chunks) byId.put(chunk.getId(), chunk);
        List<Map<String, Object>> out = new ArrayList<>();
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
            out.add(hit);
        }
        return out;
    }

    private Long longValue(Object value) {
        if (value instanceof Number n) return n.longValue();
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
