package com.platform.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.workflow.entity.WorkflowCheckpoint;
import com.platform.workflow.entity.WorkflowRun;
import com.platform.workflow.mapper.WorkflowCheckpointMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkflowCheckpointTest {

    @Test
    void writesRunStateSnapshot() {
        WorkflowService service = mock(WorkflowService.class, CALLS_REAL_METHODS);
        WorkflowCheckpointMapper mapper = mock(WorkflowCheckpointMapper.class);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "checkpointMapper", mapper);

        WorkflowRun run = new WorkflowRun();
        run.setId(9L);
        run.setTenantId(1L);
        run.setInputTokens(10L);
        run.setOutputTokens(20L);

        service.saveCheckpoint(run, "llm", "end", "RUNNING",
                List.of(Map.of("nodeId", "llm", "output", "ok")), Map.of("key", "value"), "ok");

        ArgumentCaptor<WorkflowCheckpoint> captor = ArgumentCaptor.forClass(WorkflowCheckpoint.class);
        verify(mapper).insert(captor.capture());
        WorkflowCheckpoint cp = captor.getValue();
        assertEquals(9L, cp.getRunId());
        assertEquals(1L, cp.getTenantId());
        assertEquals("llm", cp.getNodeId());
        assertEquals("end", cp.getNextNodeId());
        assertEquals("RUNNING", cp.getStatus());
        assertEquals(10L, cp.getInputTokens());
        assertEquals(20L, cp.getOutputTokens());
    }
}
