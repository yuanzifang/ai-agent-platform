package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.platform.common.context.TenantContext;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import com.platform.system.mapper.SysTenantMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeVectorAuditSchedulerTest {

    private KnowledgeDocumentMapper docMapper;
    private VectorRepository vectorRepository;
    private KnowledgeVectorAuditScheduler scheduler;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(9L);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocument.class);
        docMapper = mock(KnowledgeDocumentMapper.class);
        vectorRepository = mock(VectorRepository.class);
        scheduler = new KnowledgeVectorAuditScheduler(mock(SysTenantMapper.class),
                docMapper, vectorRepository);
        when(docMapper.update(isNull(), any())).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void reportsVectorCountMismatch() {
        KnowledgeDocument doc = doc();
        when(docMapper.selectList(any())).thenReturn(List.of(doc));
        when(vectorRepository.countByDocument(9L, 79L, 1024)).thenReturn(1L);

        Map<String, Integer> result = scheduler.auditTenant();

        assertEquals(1, result.get("checked"));
        assertEquals(1, result.get("mismatched"));
        verify(docMapper).update(isNull(), any());
    }

    @Test
    void acceptsConsistentVectorCount() {
        KnowledgeDocument doc = doc();
        when(docMapper.selectList(any())).thenReturn(List.of(doc));
        when(vectorRepository.countByDocument(9L, 79L, 1024)).thenReturn(2L);

        Map<String, Integer> result = scheduler.auditTenant();

        assertEquals(1, result.get("checked"));
        assertEquals(0, result.get("mismatched"));
    }

    private KnowledgeDocument doc() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(79L);
        doc.setTenantId(9L);
        doc.setStatus("DONE");
        doc.setChunkCount(2);
        doc.setEmbeddingDimensions(1024);
        return doc;
    }
}
