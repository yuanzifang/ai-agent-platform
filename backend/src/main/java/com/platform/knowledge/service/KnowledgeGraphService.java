package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.exception.BusinessException;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.entity.KnowledgeGraphEntity;
import com.platform.knowledge.entity.KnowledgeGraphRelation;
import com.platform.knowledge.mapper.KnowledgeGraphEntityMapper;
import com.platform.knowledge.mapper.KnowledgeGraphRelationMapper;
import com.platform.model.service.ChatModelRouter;
import com.platform.system.service.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;

/** 知识图谱 MVP：实体/关系手工维护，供可视化与后续 GraphRAG 检索扩展。 */
@Service
@RequiredArgsConstructor
public class KnowledgeGraphService {

    private final KnowledgeGraphEntityMapper entityMapper;
    private final KnowledgeGraphRelationMapper relationMapper;
    private final KnowledgePermissionService permissionService;
    private final AuditService auditService;
    private final ChatModelRouter modelRouter;
    private final ObjectMapper objectMapper;
    private final com.platform.knowledge.mapper.KnowledgeChunkMapper chunkMapper;

    public Map<String, Object> graph(Long kbId, KnowledgePermissionService.Viewer viewer) {
        permissionService.requireView(kbId, viewer);
        List<KnowledgeGraphEntity> entities = entityMapper.selectList(
                new LambdaQueryWrapper<KnowledgeGraphEntity>()
                        .eq(KnowledgeGraphEntity::getKbId, kbId)
                        .orderByAsc(KnowledgeGraphEntity::getId));
        List<KnowledgeGraphRelation> relations = relationMapper.selectList(
                new LambdaQueryWrapper<KnowledgeGraphRelation>()
                        .eq(KnowledgeGraphRelation::getKbId, kbId)
                        .orderByAsc(KnowledgeGraphRelation::getId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entities", entities);
        result.put("relations", relations);
        result.put("entityCount", entities.size());
        result.put("relationCount", relations.size());
        return result;
    }

    /**
     * GraphRAG MVP：查询/命中文本匹配实体名，沿 1 跳关系找邻居实体，
     * 拉取实体显式关联的 chunk（entity.chunk_id），按 Chunk ACL 过滤后追加。
     */
    public List<Map<String, Object>> expandChunks(Long kbId, String query, List<String> hitContents,
                                                  Set<Long> existingChunkIds,
                                                  KnowledgePermissionService.Viewer viewer) {
        List<KnowledgeGraphEntity> entities = entityMapper.selectList(
                new LambdaQueryWrapper<KnowledgeGraphEntity>()
                        .eq(KnowledgeGraphEntity::getKbId, kbId)
                        .orderByAsc(KnowledgeGraphEntity::getId));
        if (entities.isEmpty()) return List.of();
        String q = query == null ? "" : query.toLowerCase();
        String joined = (hitContents == null ? List.<String>of() : hitContents).stream()
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.joining("\n")).toLowerCase();
        List<KnowledgeGraphEntity> matched = entities.stream()
                .filter(e -> e.getName() != null && !e.getName().isBlank()
                        && (q.contains(e.getName().toLowerCase()) || joined.contains(e.getName().toLowerCase())))
                .limit(8).toList();
        if (matched.isEmpty()) return List.of();
        Set<Long> ids = new LinkedHashSet<>();
        Map<Long, String> reasons = new LinkedHashMap<>();
        Set<Long> frontier = new LinkedHashSet<>();
        for (KnowledgeGraphEntity e : matched) {
            ids.add(e.getId());
            frontier.add(e.getId());
            reasons.put(e.getId(), "matched:" + e.getName());
        }
        // 2 跳关系扩展（不引入无限遍历）
        for (int depth = 1; depth <= 2 && !frontier.isEmpty() && ids.size() < 12; depth++) {
            List<Long> current = new ArrayList<>(frontier);
            List<KnowledgeGraphRelation> relations = relationMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeGraphRelation>()
                            .eq(KnowledgeGraphRelation::getKbId, kbId)
                            .and(w -> w.in(KnowledgeGraphRelation::getFromId, current)
                                    .or().in(KnowledgeGraphRelation::getToId, current)));
            Set<Long> next = new LinkedHashSet<>();
            for (KnowledgeGraphRelation r : relations) {
                if (current.contains(r.getFromId()) && ids.add(r.getToId())) {
                    reasons.put(r.getToId(), "related" + depth + ":" + r.getRelation());
                    next.add(r.getToId());
                }
                if (current.contains(r.getToId()) && ids.add(r.getFromId())) {
                    reasons.put(r.getFromId(), "related" + depth + ":" + r.getRelation());
                    next.add(r.getFromId());
                }
                if (ids.size() >= 12) break;
            }
            frontier = next;
        }
        if (ids.size() > 12) ids = new java.util.LinkedHashSet<>(ids.stream().limit(12).toList());
        final Set<Long> selectedIds = ids;
        List<KnowledgeGraphEntity> selected = entities.stream().filter(e -> selectedIds.contains(e.getId())).toList();
        List<Map<String, Object>> extras = new ArrayList<>();
        boolean tenantWide = permissionService.tenantWide(viewer);
        for (KnowledgeGraphEntity e : selected) {
            if (e.getChunkId() == null || existingChunkIds.contains(e.getChunkId())) continue;
            com.platform.knowledge.entity.KnowledgeChunk chunk = chunkMapper.selectById(e.getChunkId());
            if (chunk == null || !Objects.equals(chunk.getKnowledgeBaseId(), kbId)) continue;
            if (!aclAllows(chunk, viewer, tenantWide)) continue;
            existingChunkIds.add(chunk.getId());
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("content", chunk.getContent());
            hit.put("score", 0.55);
            try {
                hit.put("metadata", objectMapper.writeValueAsString(Map.of(
                        "chunk_id", chunk.getId(),
                        "knowledge_base_id", kbId,
                        "graph", true,
                        "entity_id", e.getId(),
                        "entity_name", e.getName(),
                        "reason", reasons.getOrDefault(e.getId(), "graph"))));
            } catch (Exception ignored) {
                hit.put("metadata", "{\"chunk_id\":" + chunk.getId() + ",\"graph\":true}");
            }
            extras.add(hit);
            if (extras.size() >= 5) break;
        }
        return extras;
    }

    private boolean aclAllows(com.platform.knowledge.entity.KnowledgeChunk chunk,
                              KnowledgePermissionService.Viewer viewer, boolean tenantWide) {
        if (chunk.getTenantVisible() != null && chunk.getTenantVisible() == 1 && tenantWide) return true;
        try {
            List<?> users = objectMapper.readValue(chunk.getAllowedUsers() == null ? "[]" : chunk.getAllowedUsers(), List.class);
            for (Object id : users) {
                if (viewer.userId() != null && viewer.userId().equals(Long.valueOf(String.valueOf(id)))) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** AI 抽取预览/提交：text 为空时读取知识库分段；默认只预览，commit=true 才入库。 */
    public Map<String, Object> extract(Long kbId, Map<String, Object> req,
                                       KnowledgePermissionService.Viewer viewer) {
        KnowledgeBase kb = permissionService.requireEdit(kbId, viewer);
        String text = req == null ? null : text(req.get("text"), 8000);
        Long documentId = req == null ? null : number(req.get("documentId"));
        Long chunkId = req == null ? null : number(req.get("chunkId"));
        if (text == null) {
            text = loadChunkText(kbId, documentId);
        }
        if (text == null || text.isBlank()) throw new BusinessException(400, "没有可抽取的文本或分段");
        String modelName = req == null ? null : text(req.get("modelName"), 128);
        if (modelName == null) modelName = modelRouter.defaultModelName();
        String raw;
        try {
            ChatResponse response = ChatClient.create(modelRouter.resolve(modelName)).prompt()
                    .options(modelRouter.options(modelName, 0.1, List.of()))
                    .system("""
你是知识图谱抽取器。只输出 JSON，不要 Markdown：
{"entities":[{"name":"实体名","type":"类型","description":"简述"}],"relations":[{"from":"实体名","to":"实体名","relation":"关系名"}]}
实体名与关系 from/to 必须一致；最多 30 个实体、50 条关系；不要编造原文没有的事实。
""")
                    .user(text)
                    .call().chatResponse();
            raw = response == null || response.getResult() == null ? "" : response.getResult().getOutput().getText();
        } catch (Exception e) {
            throw new BusinessException(502, "知识图谱抽取调用失败：" + e.getMessage());
        }
        List<Map<String, Object>> entityDrafts = new ArrayList<>();
        List<Map<String, Object>> relationDrafts = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(stripFence(raw == null ? "" : raw));
            JsonNode entities = root.path("entities");
            if (entities.isArray()) {
                for (JsonNode node : entities) {
                    String name = text(node.path("name").asText(null), 128);
                    if (name == null) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", name);
                    m.put("entityType", text(node.path("type").asText(null), 64));
                    m.put("description", text(node.path("description").asText(null), 1024));
                    entityDrafts.add(m);
                    if (entityDrafts.size() >= 30) break;
                }
            }
            JsonNode relations = root.path("relations");
            if (relations.isArray()) {
                for (JsonNode node : relations) {
                    String from = text(node.path("from").asText(null), 128);
                    String to = text(node.path("to").asText(null), 128);
                    String relation = text(node.path("relation").asText(null), 64);
                    if (from == null || to == null || relation == null || from.equals(to)) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("from", from);
                    m.put("to", to);
                    m.put("relation", relation);
                    relationDrafts.add(m);
                    if (relationDrafts.size() >= 50) break;
                }
            }
        } catch (Exception e) {
            throw new BusinessException(422, "模型未返回合法图谱 JSON");
        }
        boolean commit = req != null && Boolean.TRUE.equals(req.get("commit"));
        if (commit) {
            commitDrafts(kb, entityDrafts, relationDrafts, documentId, chunkId, viewer);
            auditService.record(viewer.userId(), "knowledge.graph.extract", "knowledge_base",
                    String.valueOf(kbId), "SUCCESS",
                    Map.of("entities", entityDrafts.size(), "relations", relationDrafts.size()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entities", entityDrafts);
        result.put("relations", relationDrafts);
        result.put("entityCount", entityDrafts.size());
        result.put("relationCount", relationDrafts.size());
        result.put("committed", commit);
        return result;
    }

    private String loadChunkText(Long kbId, Long documentId) {
        List<com.platform.knowledge.entity.KnowledgeChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<com.platform.knowledge.entity.KnowledgeChunk>()
                        .eq(com.platform.knowledge.entity.KnowledgeChunk::getKnowledgeBaseId, kbId)
                        .eq(documentId != null, com.platform.knowledge.entity.KnowledgeChunk::getDocumentId, documentId)
                        .orderByAsc(com.platform.knowledge.entity.KnowledgeChunk::getId)
                        .last("LIMIT 5"));
        return chunks.stream().map(com.platform.knowledge.entity.KnowledgeChunk::getContent)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private void commitDrafts(KnowledgeBase kb, List<Map<String, Object>> entities,
                              List<Map<String, Object>> relations, Long documentId, Long chunkId,
                              KnowledgePermissionService.Viewer viewer) {
        Map<String, Long> ids = new LinkedHashMap<>();
        for (Map<String, Object> draft : entities) {
            String name = String.valueOf(draft.get("name"));
            String type = (String) draft.get("entityType");
            String normType = type == null ? "" : type;
            KnowledgeGraphEntity entity = entityMapper.selectOne(new LambdaQueryWrapper<KnowledgeGraphEntity>()
                    .eq(KnowledgeGraphEntity::getKbId, kb.getId())
                    .eq(KnowledgeGraphEntity::getName, name)
                    .eq(KnowledgeGraphEntity::getEntityType, normType)
                    .last("LIMIT 1"));
            if (entity == null) {
                entity = new KnowledgeGraphEntity();
                entity.setTenantId(kb.getTenantId());
                entity.setKbId(kb.getId());
                entity.setName(name);
                entity.setEntityType(normType);
                entity.setDescription((String) draft.get("description"));
                entity.setDocumentId(documentId);
                entity.setChunkId(chunkId);
                entity.setCreatedBy(viewer.userId());
                entity.setCreatedAt(LocalDateTime.now());
                entity.setUpdatedAt(LocalDateTime.now());
                entityMapper.insert(entity);
            }
            ids.put(name, entity.getId());
        }
        for (Map<String, Object> draft : relations) {
            Long from = ids.get(String.valueOf(draft.get("from")));
            Long to = ids.get(String.valueOf(draft.get("to")));
            String relation = String.valueOf(draft.get("relation"));
            if (from == null || to == null || from.equals(to)) continue;
            Long exists = relationMapper.selectCount(new LambdaQueryWrapper<KnowledgeGraphRelation>()
                    .eq(KnowledgeGraphRelation::getKbId, kb.getId())
                    .eq(KnowledgeGraphRelation::getFromId, from)
                    .eq(KnowledgeGraphRelation::getToId, to)
                    .eq(KnowledgeGraphRelation::getRelation, relation));
            if (exists != null && exists > 0) continue;
            KnowledgeGraphRelation row = new KnowledgeGraphRelation();
            row.setTenantId(kb.getTenantId());
            row.setKbId(kb.getId());
            row.setFromId(from);
            row.setToId(to);
            row.setRelation(relation);
            row.setWeight(BigDecimal.ONE);
            row.setDocumentId(documentId);
            row.setCreatedAt(LocalDateTime.now());
            relationMapper.insert(row);
        }
    }

    private String stripFence(String raw) {
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

    @Transactional
    public KnowledgeGraphEntity createEntity(Long kbId, Map<String, Object> req,
                                             KnowledgePermissionService.Viewer viewer) {
        KnowledgeBase kb = permissionService.requireEdit(kbId, viewer);
        String name = text(req.get("name"), 128);
        if (name == null) throw new BusinessException(400, "实体名称不能为空");
        String type = text(req.get("entityType"), 64);
        String normType = type == null ? "" : type;  // 与唯一键 uk_kb_entity 的 '' 语义一致（NULL 不去重）
        Long duplicate = entityMapper.selectCount(new LambdaQueryWrapper<KnowledgeGraphEntity>()
                .eq(KnowledgeGraphEntity::getKbId, kbId)
                .eq(KnowledgeGraphEntity::getName, name)
                .eq(KnowledgeGraphEntity::getEntityType, normType));
        if (duplicate != null && duplicate > 0) throw new BusinessException(409, "实体已存在：" + name);
        KnowledgeGraphEntity entity = new KnowledgeGraphEntity();
        entity.setTenantId(kb.getTenantId());
        entity.setKbId(kbId);
        entity.setName(name);
        entity.setEntityType(normType);
        entity.setDescription(text(req.get("description"), 1024));
        entity.setDocumentId(number(req.get("documentId")));
        entity.setChunkId(number(req.get("chunkId")));
        entity.setCreatedBy(viewer.userId());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        entityMapper.insert(entity);
        auditService.record(viewer.userId(), "knowledge.graph.entity.create", "knowledge_graph_entity",
                String.valueOf(entity.getId()), "SUCCESS", Map.of("kbId", kbId, "name", name));
        return entity;
    }

    @Transactional
    public KnowledgeGraphEntity updateEntity(Long kbId, Long entityId, Map<String, Object> req,
                                             KnowledgePermissionService.Viewer viewer) {
        permissionService.requireEdit(kbId, viewer);
        KnowledgeGraphEntity entity = requireEntity(kbId, entityId);
        String name = text(req.get("name"), 128);
        if (name != null) entity.setName(name);
        if (req.containsKey("entityType")) {
            String type = text(req.get("entityType"), 64);
            entity.setEntityType(type == null ? "" : type);
        }
        if (req.containsKey("description")) entity.setDescription(text(req.get("description"), 1024));
        entity.setUpdatedAt(LocalDateTime.now());
        entityMapper.updateById(entity);
        auditService.record(viewer.userId(), "knowledge.graph.entity.update", "knowledge_graph_entity",
                String.valueOf(entityId), "SUCCESS", Map.of("kbId", kbId));
        return entity;
    }

    @Transactional
    public void deleteEntity(Long kbId, Long entityId, KnowledgePermissionService.Viewer viewer) {
        permissionService.requireEdit(kbId, viewer);
        requireEntity(kbId, entityId);
        relationMapper.delete(new LambdaQueryWrapper<KnowledgeGraphRelation>()
                .eq(KnowledgeGraphRelation::getKbId, kbId)
                .and(w -> w.eq(KnowledgeGraphRelation::getFromId, entityId)
                        .or().eq(KnowledgeGraphRelation::getToId, entityId)));
        entityMapper.deleteById(entityId);
        auditService.record(viewer.userId(), "knowledge.graph.entity.delete", "knowledge_graph_entity",
                String.valueOf(entityId), "SUCCESS", Map.of("kbId", kbId));
    }

    @Transactional
    public KnowledgeGraphRelation createRelation(Long kbId, Map<String, Object> req,
                                                 KnowledgePermissionService.Viewer viewer) {
        KnowledgeBase kb = permissionService.requireEdit(kbId, viewer);
        Long fromId = number(req.get("fromId"));
        Long toId = number(req.get("toId"));
        if (fromId == null || toId == null || fromId.equals(toId)) {
            throw new BusinessException(400, "关系起点/终点不能为空且不能相同");
        }
        requireEntity(kbId, fromId);
        requireEntity(kbId, toId);
        String relation = text(req.get("relation"), 64);
        if (relation == null) throw new BusinessException(400, "关系名称不能为空");
        Long duplicate = relationMapper.selectCount(new LambdaQueryWrapper<KnowledgeGraphRelation>()
                .eq(KnowledgeGraphRelation::getKbId, kbId)
                .eq(KnowledgeGraphRelation::getFromId, fromId)
                .eq(KnowledgeGraphRelation::getToId, toId)
                .eq(KnowledgeGraphRelation::getRelation, relation));
        if (duplicate != null && duplicate > 0) throw new BusinessException(409, "关系已存在");
        KnowledgeGraphRelation row = new KnowledgeGraphRelation();
        row.setTenantId(kb.getTenantId());
        row.setKbId(kbId);
        row.setFromId(fromId);
        row.setToId(toId);
        row.setRelation(relation);
        BigDecimal weight = decimal(req.get("weight"));
        if (weight == null) weight = BigDecimal.ONE;
        // weight 表示关系强度/置信度，范围 0.01-99.99（DECIMAL(5,2)）
        weight = weight.max(new BigDecimal("0.01")).min(new BigDecimal("99.99"));
        row.setWeight(weight);
        row.setDocumentId(number(req.get("documentId")));
        row.setCreatedAt(LocalDateTime.now());
        try {
            relationMapper.insert(row);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException(409, "相同关系已存在");
        }
        auditService.record(viewer.userId(), "knowledge.graph.relation.create", "knowledge_graph_relation",
                String.valueOf(row.getId()), "SUCCESS", Map.of("kbId", kbId, "relation", relation));
        return row;
    }

    @Transactional
    public void deleteRelation(Long kbId, Long relationId, KnowledgePermissionService.Viewer viewer) {
        permissionService.requireEdit(kbId, viewer);
        KnowledgeGraphRelation row = relationMapper.selectById(relationId);
        if (row == null || !Objects.equals(row.getKbId(), kbId)) {
            throw new BusinessException(404, "关系不存在");
        }
        relationMapper.deleteById(relationId);
        auditService.record(viewer.userId(), "knowledge.graph.relation.delete", "knowledge_graph_relation",
                String.valueOf(relationId), "SUCCESS", Map.of("kbId", kbId));
    }

    @Transactional
    public void deleteByKnowledgeBase(Long kbId) {
        relationMapper.delete(new LambdaQueryWrapper<KnowledgeGraphRelation>()
                .eq(KnowledgeGraphRelation::getKbId, kbId));
        entityMapper.delete(new LambdaQueryWrapper<KnowledgeGraphEntity>()
                .eq(KnowledgeGraphEntity::getKbId, kbId));
    }

    @Transactional
    public void deleteByDocument(Long kbId, Long documentId) {
        // 先取将删实体：指向它们的关系无论登记在哪个文档下都要清，避免孤儿边
        List<KnowledgeGraphEntity> dying = entityMapper.selectList(
                new LambdaQueryWrapper<KnowledgeGraphEntity>()
                        .eq(KnowledgeGraphEntity::getKbId, kbId)
                        .eq(KnowledgeGraphEntity::getDocumentId, documentId)
                        .select(KnowledgeGraphEntity::getId));
        if (!dying.isEmpty()) {
            List<Long> ids = dying.stream().map(KnowledgeGraphEntity::getId).toList();
            relationMapper.delete(new LambdaQueryWrapper<KnowledgeGraphRelation>()
                    .eq(KnowledgeGraphRelation::getKbId, kbId)
                    .and(w -> w.in(KnowledgeGraphRelation::getFromId, ids)
                            .or().in(KnowledgeGraphRelation::getToId, ids)));
        }
        relationMapper.delete(new LambdaQueryWrapper<KnowledgeGraphRelation>()
                .eq(KnowledgeGraphRelation::getKbId, kbId)
                .eq(KnowledgeGraphRelation::getDocumentId, documentId));
        entityMapper.delete(new LambdaQueryWrapper<KnowledgeGraphEntity>()
                .eq(KnowledgeGraphEntity::getKbId, kbId)
                .eq(KnowledgeGraphEntity::getDocumentId, documentId));
    }

    private KnowledgeGraphEntity requireEntity(Long kbId, Long entityId) {
        KnowledgeGraphEntity entity = entityMapper.selectById(entityId);
        if (entity == null || !Objects.equals(entity.getKbId(), kbId)) {
            throw new BusinessException(404, "实体不存在");
        }
        return entity;
    }

    private String text(Object raw, int max) {
        if (raw == null) return null;
        String value = String.valueOf(raw).trim();
        if (value.isEmpty()) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }

    private Long number(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        try {
            return Long.valueOf(String.valueOf(raw));
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "ID 格式错误");
        }
    }

    private BigDecimal decimal(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        try {
            return new BigDecimal(String.valueOf(raw));
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "权重格式错误");
        }
    }
}
