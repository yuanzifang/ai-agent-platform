package com.platform.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.security.JwtPrincipal;
import com.platform.workflow.entity.WorkflowRun;
import com.platform.workflow.mapper.WorkflowRunMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowCheckpointResumeTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void rejectsSideEffectNodeWithoutExplicitConfirmation() {
        WorkflowService service = mock(WorkflowService.class, CALLS_REAL_METHODS);
        WorkflowRunMapper runMapper = mock(WorkflowRunMapper.class);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "runMapper", runMapper);

        WorkflowRun run = new WorkflowRun();
        run.setId(1L);
        run.setTenantId(1L);
        run.setStatus("FAILED");
        run.setCurrentNode("tool1");
        run.setDefinition("""
                {"nodes":[{"id":"tool1","type":"TOOL","next":"end"},
                          {"id":"end","type":"END"}]}
                """);
        when(runMapper.selectById(1L)).thenReturn(run);
        TenantContext.setTenantId(1L);
        JwtPrincipal principal = new JwtPrincipal(1L, 1L, false, List.of("OWNER"), List.of());

        assertThrows(BusinessException.class, () -> service.resume(1L, principal, false));
    }
}
