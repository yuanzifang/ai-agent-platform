package com.platform.workflow.service;

import com.platform.workflow.entity.WorkflowRun;
import com.platform.workflow.mapper.WorkflowCheckpointMapper;
import com.platform.workflow.mapper.WorkflowRunMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowAutoResumeSchedulerTest {

    @Test
    void resumesSafeFailedRunWhenEnabled() {
        WorkflowService service = mock(WorkflowService.class);
        WorkflowRunMapper runMapper = mock(WorkflowRunMapper.class);
        WorkflowCheckpointMapper checkpointMapper = mock(WorkflowCheckpointMapper.class);
        WorkflowRun run = new WorkflowRun();
        run.setId(9L);
        run.setTenantId(1L);
        run.setStatus("FAILED");
        when(runMapper.findFailedForAutoResume(any(Integer.class))).thenReturn(List.of(run));
        when(checkpointMapper.selectCount(any())).thenReturn(0L);

        WorkflowAutoResumeScheduler scheduler = new WorkflowAutoResumeScheduler(service, runMapper, checkpointMapper);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        scheduler.scan();

        verify(service).resume(anyLong(), any(), anyBoolean());
    }
}
