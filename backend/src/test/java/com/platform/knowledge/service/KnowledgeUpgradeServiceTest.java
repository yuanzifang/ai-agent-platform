package com.platform.knowledge.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.platform.common.context.TenantContext;
import com.platform.knowledge.entity.KnowledgeDocument;
import com.platform.knowledge.entity.KnowledgeUpgradeJob;
import com.platform.knowledge.mapper.KnowledgeDocumentMapper;
import com.platform.knowledge.mapper.KnowledgeUpgradeJobMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeUpgradeServiceTest {

    private KnowledgeUpgradeJobMapper jobMapper;
    private KnowledgeDocumentMapper docMapper;
    private KnowledgeService knowledgeService;
    private KnowledgePermissionService permissionService;
    private KnowledgeUpgradeService service;
    private final KnowledgePermissionService.Viewer viewer =
            new KnowledgePermissionService.Viewer(7L, List.of("OWNER"));

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(9L);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocument.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeUpgradeJob.class);
        jobMapper = mock(KnowledgeUpgradeJobMapper.class);
        docMapper = mock(KnowledgeDocumentMapper.class);
        knowledgeService = mock(KnowledgeService.class);
        permissionService = mock(KnowledgePermissionService.class);
        service = new KnowledgeUpgradeService(jobMapper, docMapper, knowledgeService, permissionService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void startCreatesJobAndQueuesLegacyDocuments() {
        when(jobMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            KnowledgeUpgradeJob job = invocation.getArgument(0);
            job.setId(5L);
            return 1;
        }).when(jobMapper).insert(any(KnowledgeUpgradeJob.class));
        when(docMapper.update(isNull(), any())).thenReturn(3);
        when(docMapper.selectCount(any())).thenReturn(3L, 0L, 0L, 0L);

        Map<String, Object> result = service.start(1L, viewer);

        assertEquals(5L, result.get("jobId"));
        assertEquals("RUNNING", result.get("status"));
        assertEquals(3, result.get("totalDocuments"));
        verify(permissionService).requireEdit(1L, viewer);
        verify(docMapper, org.mockito.Mockito.atLeastOnce()).update(isNull(), any());
    }

    @Test
    void cancelsRunningJob() {
        KnowledgeUpgradeJob job = new KnowledgeUpgradeJob();
        job.setId(5L);
        job.setKnowledgeBaseId(1L);
        job.setStatus("RUNNING");
        job.setTotalDocuments(2);
        when(jobMapper.selectById(5L)).thenReturn(job);
        when(jobMapper.update(isNull(), any())).thenReturn(1);
        when(docMapper.selectCount(any())).thenReturn(0L);

        Map<String, Object> result = service.cancel(1L, 5L, viewer);

        assertEquals("CANCELLED", result.get("status"));
        verify(permissionService).requireEdit(1L, viewer);
    }

    @Test
    void pausesAndResumesJob() {
        KnowledgeUpgradeJob job = new KnowledgeUpgradeJob();
        job.setId(5L);
        job.setKnowledgeBaseId(1L);
        job.setStatus("RUNNING");
        job.setTotalDocuments(2);
        when(jobMapper.selectById(5L)).thenReturn(job);
        when(jobMapper.update(isNull(), any())).thenReturn(1);
        when(docMapper.selectCount(any())).thenReturn(2L, 0L, 0L, 0L, 2L, 0L, 0L, 0L);

        Map<String, Object> paused = service.pause(1L, 5L, viewer);
        assertEquals("PAUSED", paused.get("status"));

        job.setStatus("PAUSED");
        Map<String, Object> resumed = service.resume(1L, 5L, viewer);
        assertEquals("RUNNING", resumed.get("status"));
        verify(permissionService, org.mockito.Mockito.atLeast(2)).requireEdit(1L, viewer);
    }

    @Test
    void workerSubmitsPendingDocument() {
        KnowledgeUpgradeJob job = new KnowledgeUpgradeJob();
        job.setId(5L);
        job.setKnowledgeBaseId(1L);
        job.setStatus("RUNNING");
        job.setTotalDocuments(1);
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(79L);
        doc.setKnowledgeBaseId(1L);
        doc.setStatus("DONE");
        doc.setUpgradeJobId(5L);
        doc.setUpgradeStatus("PENDING");
        when(jobMapper.selectList(any())).thenReturn(List.of(job));
        when(docMapper.selectList(any())).thenReturn(List.of(doc));
        when(docMapper.update(isNull(), any())).thenReturn(1);
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(knowledgeService.reparseForUpgrade(1L, 79L)).thenReturn(true);

        int submitted = service.processTenant(10);

        assertTrue(submitted >= 1);
        verify(knowledgeService).reparseForUpgrade(1L, 79L);
    }

    @Test
    void workerRetriesFailedDocumentAndIncrementsAttempts() {
        KnowledgeUpgradeJob job = new KnowledgeUpgradeJob();
        job.setId(5L);
        job.setKnowledgeBaseId(1L);
        job.setStatus("RUNNING");
        job.setTotalDocuments(1);
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(79L);
        doc.setKnowledgeBaseId(1L);
        doc.setStatus("FAILED");
        doc.setUpgradeJobId(5L);
        doc.setUpgradeStatus("PENDING");
        doc.setUpgradeAttempts(1);
        when(jobMapper.selectList(any())).thenReturn(List.of(job));
        when(docMapper.selectList(any())).thenReturn(List.of(doc));
        when(docMapper.update(isNull(), any())).thenReturn(1);
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(knowledgeService.reparseForUpgrade(1L, 79L)).thenReturn(true);

        int submitted = service.processTenant(10);

        assertTrue(submitted >= 1);
        verify(knowledgeService).reparseForUpgrade(1L, 79L);
        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeDocument>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(docMapper, org.mockito.Mockito.atLeastOnce()).update(isNull(), captor.capture());
        assertTrue(captor.getAllValues().stream()
                        .anyMatch(wrapper -> wrapper.getSqlSet().contains("upgrade_attempts")),
                "领取任务时应原子递增 upgrade_attempts");
    }
}
