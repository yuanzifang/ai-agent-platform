package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.billing.mapper.BillingUsageMapper;
import com.platform.billing.service.PricingService;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeGraphServiceAclTest {

    private KnowledgeGraphEntityMapper entityMapper;
    private KnowledgeGraphRelationMapper relationMapper;
    private KnowledgeGraphMentionMapper mentionMapper;
    private KnowledgeChunkMapper chunkMapper;
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
        permissionService = mock(KnowledgePermissionService.class);
        service = new KnowledgeGraphService(
                entityMapper, relationMapper, mentionMapper, mock(KnowledgeGraphJobMapper.class),
                mock(KnowledgeBaseMapper.class), mock(KnowledgeDocumentMapper.class), chunkMapper,
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
        when(mentionMapper.selectList(any())).thenReturn(List.of(mention(11L, 500L)));
        when(entityMapper.selectList(any())).thenReturn(List.of(auto));
        when(entityMapper.selectById(11L)).thenReturn(auto);
        when(mentionMapper.selectCount(any())).thenReturn(0L);
        when(relationMapper.selectCount(any())).thenReturn(1L);

        service.deleteByDocument(1L, 1L);

        verify(entityMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(entityMapper, never()).deleteById(11L);
        assertEquals("MANUAL", auto.getSource());
        assertNull(auto.getDocumentId());
        assertNull(auto.getChunkId());
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
