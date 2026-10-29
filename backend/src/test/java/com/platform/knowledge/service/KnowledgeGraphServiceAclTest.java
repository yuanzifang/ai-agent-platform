package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.billing.service.PricingService;
import com.platform.common.exception.BusinessException;
import com.platform.knowledge.entity.KnowledgeBase;
import com.platform.knowledge.entity.KnowledgeChunk;
import com.platform.knowledge.entity.KnowledgeGraphEntity;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeGraphServiceAclTest {

    private KnowledgeGraphEntityMapper entityMapper;
    private KnowledgeGraphRelationMapper relationMapper;
    private KnowledgeGraphMentionMapper mentionMapper;
    private KnowledgeChunkMapper chunkMapper;
    private KnowledgeBaseMapper kbMapper;
    private KnowledgePermissionService permissionService;
    private KnowledgeGraphService service;
    private KnowledgeBase kb;
    private KnowledgePermissionService.Viewer viewer;

    @BeforeEach
    void setUp() {
        // MyBatis-Plus 的 LambdaQueryWrapper.select(...) 需要实体 TableInfo 缓存
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeGraphEntity.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeGraphMention.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeGraphRelation.class);

        entityMapper = mock(KnowledgeGraphEntityMapper.class);
        relationMapper = mock(KnowledgeGraphRelationMapper.class);
        mentionMapper = mock(KnowledgeGraphMentionMapper.class);
        chunkMapper = mock(KnowledgeChunkMapper.class);
        kbMapper = mock(KnowledgeBaseMapper.class);
        permissionService = mock(KnowledgePermissionService.class);
        service = new KnowledgeGraphService(
                entityMapper, relationMapper, mentionMapper,
                mock(com.platform.knowledge.mapper.KnowledgeGraphCommunityMapper.class),
                mock(com.platform.knowledge.service.EmbeddingService.class),
                mock(KnowledgeGraphJobMapper.class),
                kbMapper, mock(KnowledgeDocumentMapper.class), chunkMapper,
                mock(SysConfigMapper.class), mock(BillingUsageMapper.class),
                mock(PricingService.class), permissionService, mock(AuditService.class),
                mock(ChatModelRouter.class), new ObjectMapper());

        kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setTenantId(9L);
        kb.setCreatedBy(7L);
        kb.setVisibility("TENANT");
        viewer = new KnowledgePermissionService.Viewer(7L, List.of("MEMBER"));
        when(permissionService.requireView(1L, viewer)).thenReturn(kb);
        when(permissionService.aclOf(kb))
                .thenReturn(new KnowledgePermissionService.Acl(List.of(7L), true));
        when(permissionService.canEdit(kb, viewer)).thenReturn(false);
    }

    @Test
    void graphDisabledRejectsGraphReadAndSkipsGraphRag() {
        kb.setGraphEnabled(false);
        BusinessException error = assertThrows(BusinessException.class, () -> service.graph(1L, viewer));
        assertEquals(400, error.getCode());

        when(kbMapper.selectById(1L)).thenReturn(kb);
        List<Map<String, Object>> extra = service.expandChunks(
                1L, "公开实体", List.of("公开实体"), new LinkedHashSet<>(), viewer);
        assertTrue(extra.isEmpty());
        verify(entityMapper, never()).selectList(any());
    }

    @Test
    void graphHidesAutoEntityAndRelationEvidenceFromRestrictedChunks() {
        KnowledgeGraphEntity manual = entity(10L, "手工实体", "概念", "MANUAL");
        KnowledgeGraphEntity autoOpen = entity(11L, "公开实体", "产品", "AUTO");
        KnowledgeGraphEntity autoHidden = entity(12L, "受限实体", "产品", "AUTO");
        KnowledgeGraphEntity autoOpen2 = entity(13L, "公开实体2", "产品", "AUTO");
        when(entityMapper.selectList(any())).thenReturn(List.of(manual, autoOpen, autoHidden, autoOpen2));

        KnowledgeGraphRelation visible = relation(100L, 10L, 11L, "关联", null, "MANUAL");
        KnowledgeGraphRelation hiddenEndpoint = relation(101L, 11L, 12L, "关联", null, "AUTO");
        KnowledgeGraphRelation hiddenEvidence = relation(102L, 11L, 13L, "证据受限", 503L, "AUTO");
        when(relationMapper.selectList(any())).thenReturn(List.of(visible, hiddenEndpoint, hiddenEvidence));

        // 有受限 chunk，关闭 TENANT 全员快捷路径，逐个做 chunk ACL 判断
        when(chunkMapper.selectCount(any())).thenReturn(1L);
        KnowledgeChunk open = chunk(500L, 1);
        KnowledgeChunk hidden = chunk(501L, 0);
        KnowledgeChunk open2 = chunk(502L, 1);
        KnowledgeChunk hiddenEvidenceChunk = chunk(503L, 0);
        when(chunkMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(open, hidden, open2, hiddenEvidenceChunk));
        when(mentionMapper.selectList(any())).thenReturn(List.of(
                mention(11L, 500L), mention(12L, 501L), mention(13L, 502L)));

        Map<String, Object> result = service.graph(1L, viewer);

        assertEquals(3, result.get("entityCount"));
        assertEquals(1, result.get("relationCount"));
        @SuppressWarnings("unchecked")
        List<KnowledgeGraphEntity> entities = (List<KnowledgeGraphEntity>) result.get("entities");
        @SuppressWarnings("unchecked")
        List<KnowledgeGraphRelation> relations = (List<KnowledgeGraphRelation>) result.get("relations");
        assertEquals(List.of(10L, 11L, 13L), entities.stream().map(KnowledgeGraphEntity::getId).toList());
        assertEquals(100L, relations.get(0).getId());
        assertFalse((Boolean) result.get("canEdit"));
    }

    @Test
    void deleteByDocumentKeepsAutoEntityWhenManualRelationStillReferencesIt() {
        KnowledgeGraphEntity auto = entity(11L, "被手工引用的实体", "产品", "AUTO");
        auto.setDocumentId(1L);
        auto.setChunkId(500L);
        KnowledgeGraphRelation manual = relation(100L, 11L, 12L, "关联", 500L, "MANUAL");
        when(mentionMapper.selectList(any())).thenReturn(
                List.of(mention(11L, 500L)), List.of());
        when(entityMapper.selectList(any())).thenReturn(List.of(auto));
        when(entityMapper.selectBatchIds(anyCollection())).thenReturn(List.of(auto));
        when(relationMapper.selectList(any())).thenReturn(List.of(manual));

        service.deleteByDocument(1L, 1L);

        verify(entityMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(entityMapper, never()).deleteById(anyLong());
        verify(entityMapper, never()).deleteBatchIds(anyCollection());
    }

    @Test
    void deleteByDocumentBatchesEntityLookupsAndProtectsManualRelations() {
        KnowledgeGraphEntity stillMentioned = entity(21L, "仍有证据", "产品", "AUTO");
        KnowledgeGraphEntity manuallyReferenced = entity(22L, "手工引用", "产品", "AUTO");
        KnowledgeGraphEntity orphan = entity(23L, "孤儿实体", "产品", "AUTO");
        when(mentionMapper.selectList(any())).thenReturn(
                List.of(mention(21L, 500L), mention(22L, 501L), mention(23L, 502L)),
                List.of(mention(21L, 500L)));
        when(entityMapper.selectList(any())).thenReturn(List.of());
        when(entityMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(stillMentioned, manuallyReferenced, orphan));
        when(relationMapper.selectList(any())).thenReturn(List.of(
                relation(100L, 22L, 99L, "手工关系", 501L, "MANUAL")));

        service.deleteByDocument(1L, 1L);

        verify(entityMapper, times(1)).selectBatchIds(anyCollection());
        verify(entityMapper, never()).selectById(any());
        verify(mentionMapper, times(2)).selectList(any());
        verify(mentionMapper, never()).selectCount(any());
        verify(relationMapper, times(1)).selectList(any());
        verify(relationMapper, never()).selectCount(any());
        verify(entityMapper, times(1)).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(entityMapper, times(1)).deleteBatchIds(anyCollection());
        verify(entityMapper, never()).deleteById(anyLong());
    }

    @Test
    void graphRagExpandsOneHopAndSkipsExistingChunks() {
        kb.setGraphEnabled(true);
        when(kbMapper.selectById(1L)).thenReturn(kb);

        KnowledgeGraphEntity matched = entity(10L, "退款", "概念", "AUTO");
        KnowledgeGraphEntity related = entity(11L, "退款期限", "概念", "AUTO");
        KnowledgeGraphRelation relation = relation(100L, 10L, 11L, "包含", 503L, "AUTO");
        relation.setConfidence(BigDecimal.ONE);
        when(entityMapper.selectList(any())).thenReturn(List.of(matched, related));
        when(relationMapper.selectList(any()))
                .thenReturn(List.of(relation), List.of(relation), List.of(relation));
        when(mentionMapper.selectList(any())).thenReturn(List.of(
                mention(10L, 500L), mention(11L, 501L)));
        KnowledgeChunk existingChunk = chunk(500L, 1);
        when(chunkMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                existingChunk, chunk(501L, 1), chunk(503L, 1)));

        LinkedHashSet<Long> existing = new LinkedHashSet<>();
        existing.add(500L);
        List<Map<String, Object>> extras = service.expandChunks(
                1L, "退款", List.of("退款期限"), existing, viewer);

        assertEquals(2, extras.size(), "只应返回未在向量/关键词结果中的图证据分段");
        assertEquals(List.of("内容501", "内容503"),
                extras.stream().map(h -> h.get("content")).toList());
        assertTrue(String.valueOf(extras.get(0).get("metadata")).contains("\"graph\":true"));
        assertTrue(String.valueOf(extras.get(0).get("metadata")).contains("\"entity_id\":11"));
        // expandChunks 内部复制 existing，避免污染调用方的向量/关键词命中集合
        assertEquals(1, existing.size());
    }

    @Test
    void nonFiniteConfidenceFallsBackToDefault() {
        BigDecimal value = ReflectionTestUtils.invokeMethod(service, "confidence", Double.NaN);
        assertEquals(0.8, value.doubleValue(), 0.0001);
    }

    @Test
    void graphBuildRejectedWhenGraphIsDisabled() {
        kb.setGraphEnabled(false);
        when(permissionService.requireEdit(1L, viewer)).thenReturn(kb);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.buildAll(1L, viewer));
        assertEquals(400, error.getCode());
    }

    private KnowledgeGraphEntity entity(Long id, String name, String type, String source) {
        KnowledgeGraphEntity entity = new KnowledgeGraphEntity();
        entity.setId(id);
        entity.setKbId(1L);
        entity.setTenantId(9L);
        entity.setName(name);
        entity.setNormalizedName(name);
        entity.setEntityType(type);
        entity.setSource(source);
        return entity;
    }

    private KnowledgeGraphRelation relation(Long id, Long fromId, Long toId, String name,
                                            Long chunkId, String source) {
        KnowledgeGraphRelation relation = new KnowledgeGraphRelation();
        relation.setId(id);
        relation.setKbId(1L);
        relation.setTenantId(9L);
        relation.setFromId(fromId);
        relation.setToId(toId);
        relation.setRelation(name);
        relation.setChunkId(chunkId);
        relation.setSource(source);
        return relation;
    }

    private KnowledgeChunk chunk(Long id, int tenantVisible) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setKnowledgeBaseId(1L);
        chunk.setTenantId(9L);
        chunk.setDocumentId(1L);
        chunk.setTenantVisible(tenantVisible);
        chunk.setAllowedUsers("[]");
        chunk.setContent("内容" + id);
        return chunk;
    }

    private KnowledgeGraphMention mention(Long entityId, Long chunkId) {
        KnowledgeGraphMention mention = new KnowledgeGraphMention();
        mention.setId(entityId * 10 + chunkId);
        mention.setKbId(1L);
        mention.setTenantId(9L);
        mention.setEntityId(entityId);
        mention.setDocumentId(1L);
        mention.setChunkId(chunkId);
        return mention;
    }
}
