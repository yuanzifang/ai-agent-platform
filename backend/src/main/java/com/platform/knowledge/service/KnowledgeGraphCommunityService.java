package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.exception.BusinessException;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.entity.KnowledgeGraphCommunity;
import com.platform.knowledge.entity.KnowledgeGraphEntity;
import com.platform.knowledge.entity.KnowledgeGraphRelation;
import com.platform.knowledge.mapper.KnowledgeGraphCommunityMapper;
import com.platform.model.service.ChatModelRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 社区摘要的生成、持久化与检索。
 * 权限校验和可见性计算保留在 KnowledgeGraphService；本服务只处理已通过 ACL 的社区数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeGraphCommunityService {

    private static final int MAX_SUMMARIZE = 10;

    private final KnowledgeGraphCommunityMapper communityMapper;
    private final EmbeddingService embeddingService;
    private final GraphVectorIndexService graphVectorIndexService;
    private final ChatModelRouter modelRouter;
    private final ObjectMapper objectMapper;

    @SuppressWarnings("unchecked")
    public Map<String, Object> summarize(Map<String, Object> communityData, String modelName, int limit) {
        List<Map<String, Object>> communities =
                (List<Map<String, Object>>) communityData.get("communities");
        int size = Math.max(1, Math.min(limit, MAX_SUMMARIZE));
        List<Map<String, Object>> result = new ArrayList<>();
        String model = modelName == null || modelName.isBlank() ? "deepseek-flash" : modelName;
        for (Map<String, Object> community : communities.stream().limit(size).toList()) {
            Map<String, Object> row = new LinkedHashMap<>(community);
            List<Map<String, Object>> members = (List<Map<String, Object>>) community.get("members");
            String names = members.stream().map(m -> String.valueOf(m.get("name")))
                    .limit(20).collect(Collectors.joining("、"));
            try {
                ChatResponse response = ChatClient.create(modelRouter.resolve(model)).prompt()
                        .system("你是知识图谱编辑，只输出一句不超过80字的中文社区摘要，不要解释。")
                        .user("社区实体：" + names)
                        .call().chatResponse();
                row.put("summary", response.getResult() == null ? ""
                        : response.getResult().getOutput().getText());
            } catch (Exception e) {
                log.warn("社区摘要生成失败 community={}: {}", community.get("id"), e.getMessage());
                row.put("summary", community.get("name"));
                row.put("summaryFailed", true);
            }
            result.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", model);
        out.put("communityCount", communities.size());
        out.put("communities", result);
        return out;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> rebuild(KnowledgeBase kb, Map<String, Object> communityData,
                                       String modelName, int limit) {
        Map<String, Object> generated = summarize(communityData, modelName, limit);
        List<Map<String, Object>> communities = (List<Map<String, Object>>) generated.get("communities");
        int saved = 0;
        for (Map<String, Object> community : communities) {
            if (Boolean.TRUE.equals(community.get("summaryFailed"))) {
                log.warn("社区摘要生成失败，保留已有摘要 kb={} community={}",
                        kb.getId(), community.get("id"));
                continue;
            }
            List<Map<String, Object>> members = (List<Map<String, Object>>) community.get("members");
            List<Long> ids = members.stream().map(m -> ((Number) m.get("id")).longValue())
                    .sorted().toList();
            String key = ids.stream().map(String::valueOf).collect(Collectors.joining("-"));
            KnowledgeGraphCommunity row = communityMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeGraphCommunity>()
                            .eq(KnowledgeGraphCommunity::getKbId, kb.getId())
                            .eq(KnowledgeGraphCommunity::getCommunityKey, key)
                            .last("LIMIT 1"));
            if (row == null) {
                row = new KnowledgeGraphCommunity();
                row.setTenantId(kb.getTenantId());
                row.setKbId(kb.getId());
                row.setCommunityKey(key);
                row.setCreatedAt(LocalDateTime.now());
            }
            row.setName(KnowledgeGraphSupport.str(community.get("name")));
            row.setSummary(KnowledgeGraphSupport.str(
                    community.getOrDefault("summary", community.get("name"))));
            if (row.getSummary() != null && !row.getSummary().isBlank()) {
                try {
                    float[] vector = embeddingService.embedOne(row.getSummary());
                    row.setEmbedding(objectMapper.writeValueAsString(vector));
                    row.setEmbeddingModel("default");
                    row.setDimensions(vector.length);
                } catch (Exception e) {
                    log.warn("社区摘要向量化失败 kb={} community={}: {}", kb.getId(), key, e.getMessage());
                    row.setEmbedding(null);
                    row.setEmbeddingModel(null);
                    row.setDimensions(null);
                }
            }
            row.setEntityIds(ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
            row.setEntityCount(ids.size());
            row.setTypes(((java.util.Collection<?>) community.getOrDefault("types", List.of()))
                    .stream().map(String::valueOf).collect(Collectors.joining(",")));
            row.setUpdatedAt(LocalDateTime.now());
            if (row.getId() == null) communityMapper.insert(row);
            else communityMapper.updateById(row);
            if (row.getEmbedding() != null) {
                try {
                    float[] vector = objectMapper.readValue(row.getEmbedding(), float[].class);
                    graphVectorIndexService.upsert(kb.getTenantId(), kb.getId(), "COMMUNITY",
                            row.getId(), vector, row.getEmbeddingModel());
                } catch (Exception ignored) {
                    // MySQL summary remains available if the dedicated index is unavailable
                }
            }
            saved++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("saved", saved);
        out.put("model", generated.get("model"));
        return out;
    }

    public Map<String, Object> saved(KnowledgeBase kb, Set<Long> visibleEntityIds) {
        List<KnowledgeGraphCommunity> rows = communityMapper.selectList(
                new LambdaQueryWrapper<KnowledgeGraphCommunity>()
                        .eq(KnowledgeGraphCommunity::getKbId, kb.getId())
                        .orderByDesc(KnowledgeGraphCommunity::getEntityCount)
                        .last("LIMIT 200"));
        List<KnowledgeGraphCommunity> visible = rows.stream()
                .filter(row -> fullyVisible(row.getEntityIds(), visibleEntityIds)).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("communityCount", visible.size());
        out.put("communities", visible);
        return out;
    }

    public Map<String, Object> search(KnowledgeBase kb, String query, int topK,
                                      Set<Long> visibleEntityIds) {
        if (query == null || query.isBlank()) throw new BusinessException(400, "查询内容不能为空");
        float[] q = embeddingService.embedOne(query);
        List<Map<String, Object>> indexed = graphVectorIndexService.search(
                kb.getTenantId(), kb.getId(), "COMMUNITY", q, topK);
        if (!indexed.isEmpty()) {
            List<Long> indexedIds = indexed.stream()
                    .map(hit -> ((Number) hit.get("objectId")).longValue()).toList();
            Map<Long, KnowledgeGraphCommunity> byId = communityMapper.selectBatchIds(indexedIds).stream()
                    .collect(Collectors.toMap(KnowledgeGraphCommunity::getId, c -> c));
            List<Map<String, Object>> indexedHits = new ArrayList<>();
            for (Map<String, Object> hit : indexed) {
                KnowledgeGraphCommunity row = byId.get(((Number) hit.get("objectId")).longValue());
                if (row == null || !fullyVisible(row.getEntityIds(), visibleEntityIds)) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", row.getId());
                item.put("name", row.getName());
                item.put("summary", row.getSummary());
                item.put("entityCount", row.getEntityCount());
                item.put("score", hit.get("score"));
                indexedHits.add(item);
            }
            if (!indexedHits.isEmpty()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("query", query);
                out.put("total", indexedHits.size());
                out.put("hits", indexedHits.stream().limit(Math.max(1, Math.min(topK, 50))).toList());
                out.put("index", "pgvector");
                return out;
            }
        }
        List<KnowledgeGraphCommunity> rows = communityMapper.selectList(
                new LambdaQueryWrapper<KnowledgeGraphCommunity>()
                        .eq(KnowledgeGraphCommunity::getKbId, kb.getId())
                        .isNotNull(KnowledgeGraphCommunity::getEmbedding)
                        .last("LIMIT 500"));
        List<Map<String, Object>> hits = new ArrayList<>();
        for (KnowledgeGraphCommunity row : rows) {
            if (!fullyVisible(row.getEntityIds(), visibleEntityIds)) continue;
            try {
                float[] vector = objectMapper.readValue(row.getEmbedding(), float[].class);
                if (vector.length != q.length) continue;
                double dot = 0;
                double normA = 0;
                double normB = 0;
                for (int i = 0; i < q.length; i++) {
                    dot += q[i] * vector[i];
                    normA += q[i] * q[i];
                    normB += vector[i] * vector[i];
                }
                if (normA == 0 || normB == 0) continue;
                Map<String, Object> hit = new LinkedHashMap<>();
                hit.put("id", row.getId());
                hit.put("name", row.getName());
                hit.put("summary", row.getSummary());
                hit.put("entityCount", row.getEntityCount());
                hit.put("score", dot / (Math.sqrt(normA) * Math.sqrt(normB)));
                hits.add(hit);
            } catch (Exception ignored) {
                // 单条损坏向量不影响其他社区
            }
        }
        hits.sort((a, b) -> Double.compare((Double) b.get("score"), (Double) a.get("score")));
        int size = Math.max(1, Math.min(topK, 50));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("total", hits.size());
        out.put("hits", hits.stream().limit(size).toList());
        return out;
    }
    @SuppressWarnings("unchecked")
    public Map<String, Object> communities(Map<String, Object> graphData) {
        List<KnowledgeGraphEntity> entities = (List<KnowledgeGraphEntity>) graphData.get("entities");
        List<KnowledgeGraphRelation> relations = (List<KnowledgeGraphRelation>) graphData.get("relations");
        Map<Long, KnowledgeGraphEntity> byId = entities.stream()
                .collect(Collectors.toMap(KnowledgeGraphEntity::getId, e -> e));
        Map<Long, Set<Long>> adjacency = new HashMap<>();
        for (KnowledgeGraphEntity entity : entities) adjacency.put(entity.getId(), new LinkedHashSet<>());
        for (KnowledgeGraphRelation relation : relations) {
            if (!adjacency.containsKey(relation.getFromId()) || !adjacency.containsKey(relation.getToId())) continue;
            adjacency.get(relation.getFromId()).add(relation.getToId());
            adjacency.get(relation.getToId()).add(relation.getFromId());
        }
        Set<Long> seen = new HashSet<>();
        List<Map<String, Object>> communities = new ArrayList<>();
        for (Long start : adjacency.keySet()) {
            if (!seen.add(start)) continue;
            List<Long> ids = new ArrayList<>();
            java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                Long current = queue.removeFirst();
                ids.add(current);
                for (Long next : adjacency.getOrDefault(current, Set.of())) {
                    if (seen.add(next)) queue.addLast(next);
                }
            }
            List<Map<String, Object>> members = new ArrayList<>();
            Set<String> types = new LinkedHashSet<>();
            List<String> names = new ArrayList<>();
            for (Long id : ids) {
                KnowledgeGraphEntity entity = byId.get(id);
                if (entity == null) continue;
                Map<String, Object> member = new LinkedHashMap<>();
                member.put("id", entity.getId());
                member.put("name", entity.getName());
                member.put("type", entity.getEntityType());
                members.add(member);
                if (entity.getEntityType() != null) types.add(entity.getEntityType());
                if (entity.getName() != null) names.add(entity.getName());
            }
            if (members.isEmpty()) continue;
            Map<String, Object> community = new LinkedHashMap<>();
            community.put("id", start);
            community.put("name", names.size() <= 3 ? String.join(" / ", names)
                    : String.join(" / ", names.subList(0, 3)) + " …");
            community.put("entityCount", members.size());
            community.put("types", types);
            community.put("members", members);
            communities.add(community);
        }
        communities.sort((a, b) -> Integer.compare(
                (Integer) b.get("entityCount"), (Integer) a.get("entityCount")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("communityCount", communities.size());
        out.put("communities", communities);
        return out;
    }

    /** 删除知识库的社区摘要及对应图向量；文档/知识库删除或重新解析后调用。 */
    public void deleteForKnowledgeBase(Long kbId) {
        if (kbId == null) return;
        List<KnowledgeGraphCommunity> rows = communityMapper.selectList(
                new LambdaQueryWrapper<KnowledgeGraphCommunity>()
                        .eq(KnowledgeGraphCommunity::getKbId, kbId));
        for (KnowledgeGraphCommunity row : rows) {
            if (row.getId() != null && row.getDimensions() != null) {
                graphVectorIndexService.delete(row.getTenantId(), kbId, "COMMUNITY",
                        row.getId(), row.getDimensions());
            }
        }
        communityMapper.delete(new LambdaQueryWrapper<KnowledgeGraphCommunity>()
                .eq(KnowledgeGraphCommunity::getKbId, kbId));
    }

    private boolean fullyVisible(String entityIds, Set<Long> visibleEntityIds) {
        if (entityIds == null || entityIds.isBlank() || visibleEntityIds == null) return false;
        for (String id : entityIds.split(",")) {
            try {
                if (!visibleEntityIds.contains(Long.parseLong(id.trim()))) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

}
