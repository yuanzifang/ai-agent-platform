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
            KnowledgeGraphEntity entity = entityMapper.selectOne(new LambdaQueryWrapper<KnowledgeGraphEntity>()
                    .eq(KnowledgeGraphEntity::getKbId, kb.getId())
                    .eq(KnowledgeGraphEntity::getName, name)
                    .eq(type != null, KnowledgeGraphEntity::getEntityType, type)
                    .last("LIMIT 1"));
            if (entity == null) {
                entity = new KnowledgeGraphEntity();
                entity.setTenantId(kb.getTenantId());
                entity.setKbId(kb.getId());
                entity.setName(name);
                entity.setEntityType(type);
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
        Long duplicate = entityMapper.selectCount(new LambdaQueryWrapper<KnowledgeGraphEntity>()
                .eq(KnowledgeGraphEntity::getKbId, kbId)
                .eq(KnowledgeGraphEntity::getName, name)
                .eq(type != null, KnowledgeGraphEntity::getEntityType, type));
        if (duplicate != null && duplicate > 0) throw new BusinessException(409, "实体已存在：" + name);
        KnowledgeGraphEntity entity = new KnowledgeGraphEntity();
        entity.setTenantId(kb.getTenantId());
        entity.setKbId(kbId);
        entity.setName(name);
        entity.setEntityType(type);
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
        if (req.containsKey("entityType")) entity.setEntityType(text(req.get("entityType"), 64));
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
        KnowledgeGraphRelation row = new KnowledgeGraphRelation();
        row.setTenantId(kb.getTenantId());
        row.setKbId(kbId);
        row.setFromId(fromId);
        row.setToId(toId);
        row.setRelation(relation);
        BigDecimal weight = decimal(req.get("weight"));
        row.setWeight(weight == null ? BigDecimal.ONE : weight);
        row.setDocumentId(number(req.get("documentId")));
        row.setCreatedAt(LocalDateTime.now());
        relationMapper.insert(row);
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
