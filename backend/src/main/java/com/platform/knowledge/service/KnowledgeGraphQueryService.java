package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.exception.BusinessException;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.entity.KnowledgeChunk;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.entity.KnowledgeGraphEntity;
import com.platform.knowledge.entity.KnowledgeGraphMention;
import com.platform.knowledge.entity.KnowledgeGraphRelation;
import com.platform.knowledge.mapper.KnowledgeChunkMapper;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import com.platform.knowledge.mapper.KnowledgeGraphEntityMapper;
import com.platform.knowledge.mapper.KnowledgeGraphMentionMapper;
import com.platform.knowledge.mapper.KnowledgeGraphRelationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/** 图谱读取、实体检索、详情与分段 ACL 可见性。 */
@Service
@RequiredArgsConstructor
public class KnowledgeGraphQueryService {

    private static final String SOURCE_AUTO = "AUTO";

    private final KnowledgeGraphEntityMapper entityMapper;
    private final KnowledgeGraphRelationMapper relationMapper;
    private final KnowledgeGraphMentionMapper mentionMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgePermissionService permissionService;
    private final EmbeddingService embeddingService;
    private final GraphVectorIndexService graphVectorIndexService;
    private final ObjectMapper objectMapper;

    public Map<String, Object> graph(Long kbId, KnowledgePermissionService.Viewer viewer) {
        KnowledgeBase kb = permissionService.requireView(kbId, viewer);
        ensureGraphEnabled(kb);
        List<KnowledgeGraphEntity> allEntities = entityMapper.selectList(
                new LambdaQueryWrapper<KnowledgeGraphEntity>()
                        .eq(KnowledgeGraphEntity::getKbId, kbId)
                        .orderByAsc(KnowledgeGraphEntity::getId));
        List<KnowledgeGraphRelation> allRelations = relationMapper.selectList(
                new LambdaQueryWrapper<KnowledgeGraphRelation>()
                        .eq(KnowledgeGraphRelation::getKbId, kbId)
                        .orderByAsc(KnowledgeGraphRelation::getId));

        Set<Long> visibleEntityIds = visibleEntityIds(kb, allEntities, viewer);
        List<KnowledgeGraphEntity> entities = allEntities.stream()
                .filter(e -> visibleEntityIds.contains(e.getId())).toList();
        Set<Long> ids = entities.stream().map(KnowledgeGraphEntity::getId).collect(Collectors.toSet());
        List<KnowledgeGraphRelation> relations = visibleRelations(kb, allRelations.stream()
                .filter(r -> ids.contains(r.getFromId()) && ids.contains(r.getToId())).toList(), viewer);

        Map<Long, Long> mentionCounts = visibleMentionCounts(kb, ids, viewer);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entities", entities);
        result.put("relations", relations);
        result.put("mentionCounts", mentionCounts);
        result.put("entityCount", entities.size());
        result.put("relationCount", relations.size());
        result.put("canEdit", permissionService.canEdit(kb, viewer));
        result.put("types", entities.stream()
                .map(KnowledgeGraphEntity::getEntityType)
                .filter(Objects::nonNull).filter(s -> !s.isBlank())
                .distinct().sorted().toList());
        return result;
    }

    /** 实体级语义检索。 */
    public Map<String, Object> searchEntities(Long kbId, String query, int topK,
                                              KnowledgePermissionService.Viewer viewer) {
        KnowledgeBase kb = permissionService.requireView(kbId, viewer);
        ensureGraphEnabled(kb);
        if (query == null || query.isBlank()) throw new BusinessException(400, "查询内容不能为空");
        float[] q = embeddingService.embedOne(query);
        List<Map<String, Object>> indexed = graphVectorIndexService.search(
                kb.getTenantId(), kbId, "ENTITY", q, topK);
        if (!indexed.isEmpty()) {
            List<Long> ids = indexed.stream()
                    .map(hit -> ((Number) hit.get("objectId")).longValue()).toList();
            List<KnowledgeGraphEntity> candidates = entityMapper.selectBatchIds(ids);
            Set<Long> visible = visibleEntityIds(kb, candidates, viewer);
            List<Map<String, Object>> indexedHits = new ArrayList<>();
            for (Map<String, Object> hit : indexed) {
                long id = ((Number) hit.get("objectId")).longValue();
                if (!visible.contains(id)) continue;
                KnowledgeGraphEntity entity = candidates.stream()
                        .filter(e -> e.getId().equals(id)).findFirst().orElse(null);
                if (entity == null) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", entity.getId());
                item.put("name", entity.getName());
                item.put("entityType", entity.getEntityType());
                item.put("description", entity.getDescription());
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
        List<KnowledgeGraphEntity> entities = entityMapper.selectList(
                new LambdaQueryWrapper<KnowledgeGraphEntity>()
                        .eq(KnowledgeGraphEntity::getKbId, kbId)
                        .isNotNull(KnowledgeGraphEntity::getEmbedding)
                        .last("LIMIT 500"));
        List<Map<String, Object>> hits = new ArrayList<>();
        for (KnowledgeGraphEntity entity : entities) {
            try {
                float[] vector = objectMapper.readValue(entity.getEmbedding(), float[].class);
                if (vector.length != q.length) continue;
                double dot = 0, normA = 0, normB = 0;
                for (int i = 0; i < q.length; i++) {
                    dot += q[i] * vector[i];
                    normA += q[i] * q[i];
                    normB += vector[i] * vector[i];
                }
                if (normA == 0 || normB == 0) continue;
                Map<String, Object> hit = new LinkedHashMap<>();
                hit.put("id", entity.getId());
                hit.put("name", entity.getName());
                hit.put("entityType", entity.getEntityType());
                hit.put("description", entity.getDescription());
                hit.put("score", dot / (Math.sqrt(normA) * Math.sqrt(normB)));
                hits.add(hit);
            } catch (Exception ignored) {
                // 单条损坏向量不影响其他实体
            }
        }
        hits.sort((a, b) -> Double.compare((Double) b.get("score"), (Double) a.get("score")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("total", hits.size());
        out.put("hits", hits.stream().limit(Math.max(1, Math.min(topK, 50))).toList());
        return out;
    }

    /** 实体详情：提及证据、关联关系、来源文档。 */
    public Map<String, Object> entityDetail(Long kbId, Long entityId,
                                            KnowledgePermissionService.Viewer viewer) {
        KnowledgeBase kb = permissionService.requireView(kbId, viewer);
        ensureGraphEnabled(kb);
        KnowledgeGraphEntity entity = requireEntity(kbId, entityId);
        if (!visibleEntityIds(kb, List.of(entity), viewer).contains(entityId)) {
            throw new BusinessException(404, "实体不存在");
        }
        List<KnowledgeGraphMention> mentions = mentionMapper.selectList(
                new LambdaQueryWrapper<KnowledgeGraphMention>()
                        .eq(KnowledgeGraphMention::getKbId, kbId)
                        .eq(KnowledgeGraphMention::getEntityId, entityId)
                        .orderByDesc(KnowledgeGraphMention::getId));
        List<Map<String, Object>> mentionRows = mentionRows(kb, mentions, viewer);

        List<KnowledgeGraphRelation> relations = relationMapper.selectList(
                new LambdaQueryWrapper<KnowledgeGraphRelation>()
                        .eq(KnowledgeGraphRelation::getKbId, kbId)
                        .and(w -> w.eq(KnowledgeGraphRelation::getFromId, entityId)
                                .or().eq(KnowledgeGraphRelation::getToId, entityId)));
        Set<Long> neighborIds = new LinkedHashSet<>();
        for (KnowledgeGraphRelation relation : relations) {
            neighborIds.add(relation.getFromId());
            neighborIds.add(relation.getToId());
        }
        neighborIds.remove(entityId);
        List<KnowledgeGraphEntity> neighbors = neighborIds.isEmpty() ? List.of()
                : entityMapper.selectBatchIds(neighborIds);
        Set<Long> visibleNeighborIds = visibleEntityIds(kb, neighbors, viewer);
        relations = relations.stream().filter(relation -> {
            Long other = relation.getFromId().equals(entityId)
                    ? relation.getToId() : relation.getFromId();
            return visibleNeighborIds.contains(other);
        }).toList();
        relations = visibleRelations(kb, relations, viewer);
        Map<Long, String> names = new HashMap<>();
        for (KnowledgeGraphEntity neighbor : neighbors) {
            if (visibleNeighborIds.contains(neighbor.getId())) {
                names.put(neighbor.getId(), neighbor.getName());
            }
        }
        List<Map<String, Object>> relationRows = new ArrayList<>();
        for (KnowledgeGraphRelation relation : relations) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", relation.getId());
            row.put("fromId", relation.getFromId());
            row.put("toId", relation.getToId());
            row.put("fromName", relation.getFromId().equals(entityId)
                    ? entity.getName() : names.getOrDefault(relation.getFromId(), "#" + relation.getFromId()));
            row.put("toName", relation.getToId().equals(entityId)
                    ? entity.getName() : names.getOrDefault(relation.getToId(), "#" + relation.getToId()));
            row.put("relation", relation.getRelation());
            row.put("weight", relation.getWeight());
            row.put("confidence", relation.getConfidence());
            row.put("source", relation.getSource());
            row.put("evidence", relation.getEvidence());
            row.put("documentId", relation.getDocumentId());
            row.put("chunkId", relation.getChunkId());
            relationRows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entity", entity);
        result.put("mentions", mentionRows);
        result.put("relations", relationRows);
        result.put("mentionCount", mentionRows.size());
        return result;
    }

    List<Map<String, Object>> mentionRows(KnowledgeBase kb, List<KnowledgeGraphMention> mentions,
                                                   KnowledgePermissionService.Viewer viewer) {
        if (mentions.isEmpty()) return List.of();
        Set<Long> chunkIds = mentions.stream().map(KnowledgeGraphMention::getChunkId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, KnowledgeChunk> chunks = chunkIds.isEmpty() ? Map.of()
                : chunkMapper.selectBatchIds(chunkIds).stream()
                .collect(Collectors.toMap(KnowledgeChunk::getId, c -> c));
        boolean tenantWide = permissionService.aclOf(kb).tenantWide();
        Set<Long> visibleChunks = chunks.values().stream()
                .filter(c -> Objects.equals(c.getKnowledgeBaseId(), kb.getId())
                        && chunkVisible(c, viewer, tenantWide))
                .map(KnowledgeChunk::getId).collect(Collectors.toSet());
        Set<Long> docIds = mentions.stream().map(KnowledgeGraphMention::getDocumentId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> docs = docIds.isEmpty() ? Map.of()
                : documentMapper.selectBatchIds(docIds).stream()
                .filter(d -> Objects.equals(d.getKnowledgeBaseId(), kb.getId()))
                .collect(Collectors.toMap(KnowledgeDocument::getId, KnowledgeDocument::getFileName));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (KnowledgeGraphMention mention : mentions) {
            if (mention.getChunkId() != null && !visibleChunks.contains(mention.getChunkId())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", mention.getId());
            row.put("documentId", mention.getDocumentId());
            row.put("documentName", docs.get(mention.getDocumentId()));
            row.put("chunkId", mention.getChunkId());
            row.put("chunkIndex", mention.getChunkId() == null ? null
                    : chunks.get(mention.getChunkId()) == null ? null
                    : chunks.get(mention.getChunkId()).getChunkIndex());
            row.put("quote", mention.getQuote());
            row.put("confidence", mention.getConfidence());
            row.put("extractor", mention.getExtractor());
            row.put("createdAt", mention.getCreatedAt());
            rows.add(row);
        }
        return rows;
    }

    Set<Long> visibleEntityIds(KnowledgeBase kb, List<KnowledgeGraphEntity> entities,
                                       KnowledgePermissionService.Viewer viewer) {
        if (entities.isEmpty()) return Set.of();
        KnowledgePermissionService.Acl acl = permissionService.aclOf(kb);
        boolean tenantWide = acl.tenantWide();
        if (tenantWide) {
            Long restricted = chunkMapper.selectCount(new LambdaQueryWrapper<KnowledgeChunk>()
                    .eq(KnowledgeChunk::getKnowledgeBaseId, kb.getId())
                    .and(w -> w.eq(KnowledgeChunk::getTenantVisible, 0)
                            .or().isNull(KnowledgeChunk::getTenantVisible)));
            if (restricted == null || restricted == 0) {
                return entities.stream().map(KnowledgeGraphEntity::getId).collect(Collectors.toSet());
            }
        }
        Set<Long> entityIds = entities.stream().map(KnowledgeGraphEntity::getId).collect(Collectors.toSet());
        List<KnowledgeGraphMention> mentions = mentionMapper.selectList(
                new LambdaQueryWrapper<KnowledgeGraphMention>()
                        .eq(KnowledgeGraphMention::getKbId, kb.getId())
                        .in(KnowledgeGraphMention::getEntityId, entityIds));
        Map<Long, List<Long>> chunksByEntity = new HashMap<>();
        Set<Long> chunkIds = new HashSet<>();
        for (KnowledgeGraphMention mention : mentions) {
            if (mention.getChunkId() == null) continue;
            chunksByEntity.computeIfAbsent(mention.getEntityId(), k -> new ArrayList<>())
                    .add(mention.getChunkId());
            chunkIds.add(mention.getChunkId());
        }
        // 旧数据可能只有 entity.chunk_id，没有 mention 行，也要纳入 ACL 判断
        for (KnowledgeGraphEntity entity : entities) {
            if (SOURCE_AUTO.equals(entity.getSource()) && entity.getChunkId() != null) {
                chunkIds.add(entity.getChunkId());
            }
        }
        Map<Long, KnowledgeChunk> chunks = chunkIds.isEmpty() ? Map.of()
                : chunkMapper.selectBatchIds(chunkIds).stream()
                .collect(Collectors.toMap(KnowledgeChunk::getId, c -> c));
        Set<Long> visibleChunks = chunks.values().stream()
                .filter(c -> Objects.equals(c.getKnowledgeBaseId(), kb.getId())
                        && chunkVisible(c, viewer, tenantWide))
                .map(KnowledgeChunk::getId).collect(Collectors.toSet());
        Set<Long> visible = new HashSet<>();
        for (KnowledgeGraphEntity entity : entities) {
            if (!SOURCE_AUTO.equals(entity.getSource())) {
                visible.add(entity.getId());
                continue;
            }
            List<Long> entityChunks = chunksByEntity.getOrDefault(entity.getId(), List.of());
            if (entityChunks.stream().anyMatch(visibleChunks::contains)) {
                visible.add(entity.getId());
            } else if (entityChunks.isEmpty() && entity.getChunkId() != null
                    && visibleChunks.contains(entity.getChunkId())) {
                visible.add(entity.getId());
            } else if (entityChunks.isEmpty() && entity.getChunkId() == null) {
                // 兼容迁移前自动生成、但没有证据行的旧数据
                visible.add(entity.getId());
            }
        }
        return visible;
    }

    /** 图谱卡片上的提及数也只统计当前用户可见的证据分段，避免数量和详情不一致。 */
    Map<Long, Long> visibleMentionCounts(KnowledgeBase kb, Set<Long> entityIds,
                                                 KnowledgePermissionService.Viewer viewer) {
        if (entityIds.isEmpty()) return Map.of();
        List<KnowledgeGraphMention> mentions = mentionMapper.selectList(
                new LambdaQueryWrapper<KnowledgeGraphMention>()
                        .eq(KnowledgeGraphMention::getKbId, kb.getId())
                        .in(KnowledgeGraphMention::getEntityId, entityIds));
        if (mentions.isEmpty()) return Map.of();
        KnowledgePermissionService.Acl acl = permissionService.aclOf(kb);
        boolean tenantWide = acl.tenantWide();
        boolean allVisible = false;
        if (tenantWide) {
            Long restricted = chunkMapper.selectCount(new LambdaQueryWrapper<KnowledgeChunk>()
                    .eq(KnowledgeChunk::getKnowledgeBaseId, kb.getId())
                    .and(w -> w.eq(KnowledgeChunk::getTenantVisible, 0)
                            .or().isNull(KnowledgeChunk::getTenantVisible)));
            allVisible = restricted == null || restricted == 0;
        }
        Map<Long, Long> counts = new HashMap<>();
        if (allVisible) {
            for (KnowledgeGraphMention mention : mentions) {
                counts.merge(mention.getEntityId(), 1L, Long::sum);
            }
            return counts;
        }
        Set<Long> chunkIds = mentions.stream().map(KnowledgeGraphMention::getChunkId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, KnowledgeChunk> chunks = chunkIds.isEmpty() ? Map.of()
                : chunkMapper.selectBatchIds(chunkIds).stream()
                .collect(Collectors.toMap(KnowledgeChunk::getId, c -> c));
        for (KnowledgeGraphMention mention : mentions) {
            if (mention.getChunkId() == null) {
                counts.merge(mention.getEntityId(), 1L, Long::sum);
                continue;
            }
            KnowledgeChunk chunk = chunks.get(mention.getChunkId());
            if (chunk != null && Objects.equals(chunk.getKnowledgeBaseId(), kb.getId())
                    && chunkVisible(chunk, viewer, tenantWide)) {
                counts.merge(mention.getEntityId(), 1L, Long::sum);
            }
        }
        return counts;
    }

    boolean chunkVisible(KnowledgeChunk chunk, KnowledgePermissionService.Viewer viewer,
                                 boolean tenantWide) {
        if (chunk.getTenantVisible() != null && chunk.getTenantVisible() == 1 && tenantWide) return true;
        if (viewer == null || viewer.userId() == null) return false;
        try {
            List<?> users = objectMapper.readValue(chunk.getAllowedUsers() == null ? "[]" : chunk.getAllowedUsers(), List.class);
            for (Object id : users) {
                if (viewer.userId().equals(Long.valueOf(String.valueOf(id)))) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 关系的证据分段同样受 chunk ACL 约束；手工关系不依赖自动证据时保留。 */
    private List<KnowledgeGraphRelation> visibleRelations(KnowledgeBase kb,
                                                          List<KnowledgeGraphRelation> relations,
                                                          KnowledgePermissionService.Viewer viewer) {
        if (relations.isEmpty()) return relations;
        Set<Long> chunkIds = relations.stream().map(KnowledgeGraphRelation::getChunkId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (chunkIds.isEmpty()) return relations;
        boolean tenantWide = permissionService.aclOf(kb).tenantWide();
        Map<Long, KnowledgeChunk> chunks = chunkMapper.selectBatchIds(chunkIds).stream()
                .collect(Collectors.toMap(KnowledgeChunk::getId, c -> c));
        return relations.stream().filter(relation -> {
            if (relation.getChunkId() == null) return true;
            KnowledgeChunk chunk = chunks.get(relation.getChunkId());
            if (chunk == null || !Objects.equals(chunk.getKnowledgeBaseId(), kb.getId())) {
                return !SOURCE_AUTO.equals(relation.getSource());
            }
            return chunkVisible(chunk, viewer, tenantWide);
        }).toList();
    }

    private void ensureGraphEnabled(KnowledgeBase kb) {
        if (kb == null || !Boolean.TRUE.equals(kb.getGraphEnabled())) {
            throw new BusinessException(400, "知识图谱未启用，请先在知识库设置中开启");
        }
    }

    private KnowledgeGraphEntity requireEntity(Long kbId, Long entityId) {
        KnowledgeGraphEntity entity = entityMapper.selectById(entityId);
        if (entity == null || !Objects.equals(entity.getKbId(), kbId)) {
            throw new BusinessException(404, "实体不存在");
        }
        return entity;
    }


    Set<Long> visibleEntityIdsForKb(KnowledgeBase kb, KnowledgePermissionService.Viewer viewer) {
        List<KnowledgeGraphEntity> entities = entityMapper.selectList(
                new LambdaQueryWrapper<KnowledgeGraphEntity>()
                        .eq(KnowledgeGraphEntity::getKbId, kb.getId()));
        return visibleEntityIds(kb, entities, viewer);
    }

}
