package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.exception.BusinessException;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.entity.KnowledgeGraphEntity;
import com.platform.knowledge.entity.KnowledgeGraphRelation;
import com.platform.knowledge.mapper.KnowledgeGraphEntityMapper;
import com.platform.knowledge.mapper.KnowledgeGraphRelationMapper;
import com.platform.system.service.AuditService;
import lombok.RequiredArgsConstructor;
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
