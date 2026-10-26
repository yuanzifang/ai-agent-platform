package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.platform.common.context.TenantContext;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.entity.KnowledgeVectorOutbox;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import com.platform.knowledge.mapper.KnowledgeVectorOutboxMapper;
import com.platform.system.mapper.SysTenantMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeVectorOutboxSchedulerTest {

    private KnowledgeVectorOutboxMapper outboxMapper;
    private KnowledgeDocumentMapper docMapper;
    private KnowledgeService knowledgeService;
    private KnowledgeVectorOutboxScheduler scheduler;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(9L);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeVectorOutbox.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocument.class);
        outboxMapper = mock(KnowledgeVectorOutboxMapper.class);
        docMapper = mock(KnowledgeDocumentMapper.class);
        knowledgeService = mock(KnowledgeService.class);
        scheduler = new KnowledgeVectorOutboxScheduler(mock(SysTenantMapper.class),
                outboxMapper, docMapper, knowledgeService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void marksOutboxDoneWhenDocumentAlreadySucceeded() {
        KnowledgeVectorOutbox task = task(1L, 79L, 0);
        KnowledgeDocument doc = doc(79L, "DONE", null);
        when(outboxMapper.selectList(any())).thenReturn(List.of(task));
        when(outboxMapper.update(isNull(), any())).thenReturn(1);
        when(docMapper.selectById(79L)).thenReturn(doc);

        int recovered = scheduler.processTenant();

        assertEquals(0, recovered);
        verify(knowledgeService, never()).retryInterruptedForOutbox(79L);
    }

    @Test
    void reopensInterruptedParseAfterStaleWindow() {
        KnowledgeVectorOutbox task = task(1L, 79L, 1);
        KnowledgeDocument doc = doc(79L, "PARSING", LocalDateTime.now().minusHours(2));
        when(outboxMapper.selectList(any())).thenReturn(List.of(task));
        when(outboxMapper.update(isNull(), any())).thenReturn(1);
        when(docMapper.selectById(79L)).thenReturn(doc);
        when(knowledgeService.retryInterruptedForOutbox(79L)).thenReturn(true);

        int recovered = scheduler.processTenant();

        assertEquals(1, recovered);
        verify(knowledgeService).retryInterruptedForOutbox(79L);
    }

    @Test
    void givesUpAfterThreeOutboxAttempts() {
        KnowledgeVectorOutbox task = task(1L, 79L, 3);
        KnowledgeDocument doc = doc(79L, "PARSING", LocalDateTime.now().minusHours(2));
        when(outboxMapper.selectList(any())).thenReturn(List.of(task));
        when(outboxMapper.update(isNull(), any())).thenReturn(1);
        when(docMapper.selectById(79L)).thenReturn(doc);

        int recovered = scheduler.processTenant();

        assertEquals(0, recovered);
        verify(knowledgeService).failInterruptedForOutbox(79L,
                "MySQL/PgVector 同步超过 3 次未完成，已清理残留；请重新解析");
    }

    private KnowledgeVectorOutbox task(Long id, Long documentId, int attempts) {
        KnowledgeVectorOutbox task = new KnowledgeVectorOutbox();
        task.setId(id);
        task.setDocumentId(documentId);
        task.setStatus("PENDING");
        task.setAttempts(attempts);
        task.setAvailableAt(LocalDateTime.now().minusMinutes(1));
        return task;
    }

    private KnowledgeDocument doc(Long id, String status, LocalDateTime startedAt) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(id);
        doc.setStatus(status);
        doc.setParseStartedAt(startedAt);
        return doc;
    }
}
