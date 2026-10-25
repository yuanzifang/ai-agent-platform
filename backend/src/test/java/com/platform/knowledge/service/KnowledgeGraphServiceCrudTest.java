package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.billing.service.PricingService;
import com.platform.common.exception.BusinessException;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.entity.KnowledgeGraphEntity;
import com.platform.knowledge.entity.KnowledgeGraphJob;
import com.platform.knowledge.entity.KnowledgeGraphMention;
import com.platform.knowledge.entity.KnowledgeGraphRelation;
import com.platform.knowledge.mapper.KnowledgeBaseMapper;
import com.platform.knowledge.mapper.KnowledgeChunkMapper;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import com.platform.knowledge.mapper.KnowledgeGraphEntityMapper;
import com.platform.knowledge.mapper.KnowledgeGraphJobMapper;
import com.platform.knowledge.mapper.KnowledgeGraphMentionMapper;
import com.platform.knowledge.mapper.KnowledgeGraphRelationMapper;
import com.platform.model.service.ChatModelRouter;
import com.platform.system.mapper.SysConfigMapper;
import com.platform.system.service.AuditService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 图谱人工维护边界：MANUAL 来源、重复校验、构建任务门禁。 */
class KnowledgeGraphServiceCrudTest {

    private KnowledgeGraphEntityMapper entityMapper;
    private KnowledgeGraphRelationMapper relationMapper;
    private KnowledgeGraphMentionMapper mentionMapper;
    private KnowledgeGraphJobMapper jobMapper;
    private KnowledgeBaseMapper kbMapper;
    private KnowledgeDocumentMapper documentMapper;
    private KnowledgePermissionService permissionService;
    private ChatModelRouter modelRouter;
    private AuditService auditService;
    private KnowledgeGraphService service;
    private KnowledgeBase kb;
    private final KnowledgePermissionService.Viewer viewer =
            new KnowledgePermissionService.Viewer(7L, List.of("OWNER"));

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeGraphEntity.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeGraphRelation.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeGraphMention.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeGraphJob.class);

        entityMapper = mock(KnowledgeGraphEntityMapper.class);
        relationMapper = mock(KnowledgeGraphRelationMapper.class);
        mentionMapper = mock(KnowledgeGraphMentionMapper.class);
        jobMapper = mock(KnowledgeGraphJobMapper.class);
        kbMapper = mock(KnowledgeBaseMapper.class);
        documentMapper = mock(KnowledgeDocumentMapper.class);
        permissionService = mock(KnowledgePermissionService.class);
        modelRouter = mock(ChatModelRouter.class);
        auditService = mock(AuditService.class);
        service = new KnowledgeGraphService(entityMapper, relationMapper, mentionMapper, jobMapper,
                kbMapper, documentMapper, mock(KnowledgeChunkMapper.class),
                mock(SysConfigMapper.class), mock(BillingUsageMapper.class),
                mock(PricingService.class), permissionService, auditService, modelRouter,
                new ObjectMapper());

        kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setTenantId(9L);
        kb.setCreatedBy(7L);
        kb.setVisibility("TENANT");
        kb.setGraphEnabled(true);
        when(permissionService.requireEdit(1L, viewer)).thenReturn(kb);
    }

    @Test
    void createEntityBecomesManualAndAudited() {
        when(entityMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            KnowledgeGraphEntity row = invocation.getArgument(0);
            row.setId(10L);
            return 1;
        }).when(entityMapper).insert(any(KnowledgeGraphEntity.class));

        KnowledgeGraphEntity entity = service.createEntity(1L,
                Map.of("name", " 退款政策 ", "entityType", "概念", "description", "说明"), viewer);

        assertEquals(10L, entity.getId());
        assertEquals("退款政策", entity.getName());
        assertEquals("退款政策", entity.getNormalizedName());
        assertEquals("概念", entity.getEntityType());
        assertEquals("MANUAL", entity.getSource());
        assertEquals(9L, entity.getTenantId());
        assertEquals(7L, entity.getCreatedBy());
        verify(auditService).record(7L, "knowledge.graph.entity.create",
                "knowledge_graph_entity", "10", "SUCCESS",
                Map.of("kbId", 1L, "name", "退款政策"));
    }

    @Test
    void createEntityRejectsDuplicateNameAndType() {
        KnowledgeGraphEntity existing = entity(10L, "退款政策", "概念");
        when(entityMapper.selectOne(any())).thenReturn(existing);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createEntity(1L,
                        Map.of("name", "退款政策", "entityType", "概念"), viewer));

        assertEquals(409, error.getCode());
        verify(entityMapper, never()).insert(any(KnowledgeGraphEntity.class));
    }

    @Test
    void updateEntityMarksAutoEntityAsManual() {
        KnowledgeGraphEntity auto = entity(10L, "退款政策", "概念");
        auto.setSource("AUTO");
        when(entityMapper.selectById(10L)).thenReturn(auto);
        when(entityMapper.selectOne(any())).thenReturn(null);

        KnowledgeGraphEntity updated = service.updateEntity(1L, 10L,
                Map.of("description", "人工补充"), viewer);

        assertEquals("MANUAL", updated.getSource());
        assertEquals("人工补充", updated.getDescription());
        verify(entityMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(auditService).record(7L, "knowledge.graph.entity.update",
                "knowledge_graph_entity", "10", "SUCCESS", Map.of("kbId", 1L));
    }

    @Test
    void createRelationValidatesEndpointsAndClampsWeight() {
        when(entityMapper.selectById(10L)).thenReturn(entity(10L, "A", "概念"));
        when(entityMapper.selectById(11L)).thenReturn(entity(11L, "B", "概念"));
        when(relationMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            KnowledgeGraphRelation row = invocation.getArgument(0);
            row.setId(20L);
            return 1;
        }).when(relationMapper).insert(any(KnowledgeGraphRelation.class));

        KnowledgeGraphRelation relation = service.createRelation(1L,
                Map.of("fromId", 10L, "toId", 11L, "relation", "关联", "weight", 1000), viewer);

        assertEquals(20L, relation.getId());
        assertEquals("MANUAL", relation.getSource());
        assertEquals(new BigDecimal("99.99"), relation.getWeight());
        assertEquals(BigDecimal.ONE, relation.getConfidence());
        verify(auditService).record(7L, "knowledge.graph.relation.create",
                "knowledge_graph_relation", "20", "SUCCESS",
                Map.of("kbId", 1L, "relation", "关联"));
    }

    @Test
    void createRelationRejectsSelfLoopBeforeLookup() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createRelation(1L,
                        Map.of("fromId", 10L, "toId", 10L, "relation", "自环"), viewer));

        assertEquals(400, error.getCode());
        verify(entityMapper, never()).selectById(any());
    }

    @Test
    void deleteEntityCascadesRelationsAndMentions() {
        when(entityMapper.selectById(10L)).thenReturn(entity(10L, "A", "概念"));

        service.deleteEntity(1L, 10L, viewer);

        verify(relationMapper).delete(any());
        verify(mentionMapper).delete(any());
        verify(entityMapper).deleteById(10L);
        verify(auditService).record(7L, "knowledge.graph.entity.delete",
                "knowledge_graph_entity", "10", "SUCCESS", Map.of("kbId", 1L));
    }

    @Test
    void buildAllReturnsExistingRunningJobWithoutDuplicating() {
        KnowledgeGraphJob running = new KnowledgeGraphJob();
        running.setId(5L);
        running.setKbId(1L);
        running.setStatus("RUNNING");
        when(jobMapper.selectOne(any())).thenReturn(running);

        Map<String, Object> result = service.buildAll(1L, viewer);

        assertEquals(5L, result.get("id"));
        assertEquals("RUNNING", result.get("status"));
        verify(jobMapper, never()).insert(any(KnowledgeGraphJob.class));
    }

    @Test
    void buildAllRejectsWhenNoDefaultModelWithoutClearingGraph() {
        when(jobMapper.selectOne(any())).thenReturn(null);
        when(modelRouter.defaultModelName()).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.buildAll(1L, viewer));

        assertEquals(400, error.getCode());
        verify(jobMapper, never()).insert(any(KnowledgeGraphJob.class));
        verify(entityMapper, never()).delete(any());
    }

    private KnowledgeGraphEntity entity(Long id, String name, String type) {
        KnowledgeGraphEntity entity = new KnowledgeGraphEntity();
        entity.setId(id);
        entity.setKbId(1L);
        entity.setTenantId(9L);
        entity.setName(name);
        entity.setNormalizedName(name);
        entity.setEntityType(type);
        entity.setSource("MANUAL");
        return entity;
    }
}
