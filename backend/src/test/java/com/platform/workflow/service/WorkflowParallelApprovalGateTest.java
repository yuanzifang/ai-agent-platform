package com.platform.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.AuditService;
import com.platform.system.service.NotificationService;
import com.platform.system.mapper.SysUserMapper;
import com.platform.workflow.entity.WorkflowApproval;
import com.platform.workflow.entity.WorkflowNodeRun;
import com.platform.workflow.entity.WorkflowRun;
import com.platform.workflow.mapper.WorkflowApprovalMapper;
import com.platform.workflow.mapper.WorkflowCheckpointMapper;
import com.platform.workflow.mapper.WorkflowNodeRunMapper;
import com.platform.workflow.mapper.WorkflowRunMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 并行网关前置审批门（回归 CI E2E「工作流并行分支审批」抓到的空角色 NPE）：
 * 分支内 APPROVAL 角色缺失时不再崩溃，且门禁取分支内最强角色。
 */
class WorkflowParallelApprovalGateTest {

    @Test
    void branchesWithApprovalNodesPauseRunAndMergeStrongestRole() throws Exception {
        WorkflowService service = mock(WorkflowService.class, CALLS_REAL_METHODS);
        WorkflowApprovalMapper approvalMapper = mock(WorkflowApprovalMapper.class);
        WorkflowRunMapper runMapper = mock(WorkflowRunMapper.class);
        WorkflowNodeRunMapper nodeRunMapper = mock(WorkflowNodeRunMapper.class);
        WorkflowCheckpointMapper checkpointMapper = mock(WorkflowCheckpointMapper.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        NotificationService notificationService = mock(NotificationService.class);
        AuditService auditService = mock(AuditService.class);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "approvalMapper", approvalMapper);
        ReflectionTestUtils.setField(service, "runMapper", runMapper);
        ReflectionTestUtils.setField(service, "nodeRunMapper", nodeRunMapper);
        ReflectionTestUtils.setField(service, "checkpointMapper", checkpointMapper);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "notificationService", notificationService);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        when(userMapper.findOwnerAdminUserIds(1L)).thenReturn(List.of(2L));
        // 模拟 MyBatis-Plus insert 回填主键；门禁末尾的审计 Map.of 依赖非空 id
        when(approvalMapper.insert(any(WorkflowApproval.class))).thenAnswer(inv -> {
            inv.getArgument(0, WorkflowApproval.class).setId(99L);
            return 1;
        });

        // 网关无角色；a1 无角色（回归点：旧代码对 null role 直接 isBlank NPE），a2 要求 ADMIN
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        nodes.put("a1", Map.of("id", "a1", "type", "APPROVAL", "name", "分支A审批", "next", "s1"));
        nodes.put("a2", Map.of("id", "a2", "type", "APPROVAL", "name", "分支B审批",
                "requiredRole", "ADMIN", "next", "s2"));
        nodes.put("s1", Map.of("id", "s1", "type", "STATE", "stateKey", "pa", "valueTemplate", "pa"));
        nodes.put("s2", Map.of("id", "s2", "type", "STATE", "stateKey", "pb", "valueTemplate", "pb"));
        Map<String, Object> parallel = Map.of("id", "parallel1", "type", "PARALLEL",
                "branches", List.of("a1", "a2"), "joinNode", "join1", "strategy", "ALL_OR_NOTHING");

        WorkflowRun run = new WorkflowRun();
        run.setId(11L);
        run.setAgentId(1L);
        run.setTenantId(1L);
        run.setStatus("RUNNING");
        run.setUserId(7L);
        WorkflowNodeRun nr = new WorkflowNodeRun();
        JwtPrincipal principal = new JwtPrincipal(7L, 1L, false, List.of("OWNER"), List.of());

        Method gate = WorkflowService.class.getDeclaredMethod("ensureParallelApprovals",
                Map.class, String.class, WorkflowRun.class, Map.class, WorkflowNodeRun.class, JwtPrincipal.class);
        gate.setAccessible(true);
        boolean proceed = (boolean) gate.invoke(service, parallel, "parallel1", run, nodes, nr, principal);

        assertFalse(proceed, "分支含审批节点时应暂停等待统一审批");
        assertEquals("WAITING_APPROVAL", run.getStatus());
        assertEquals("parallel1", run.getCurrentNode());
        ArgumentCaptor<WorkflowApproval> captor = ArgumentCaptor.forClass(WorkflowApproval.class);
        verify(approvalMapper).insert(captor.capture());
        assertEquals("ADMIN", captor.getValue().getRequiredRole(), "门禁应取分支内最强角色");
        assertEquals("PENDING", captor.getValue().getStatus());
    }

    @Test
    void gatewayWithoutApprovalBranchesProceedsImmediately() throws Exception {
        WorkflowService service = mock(WorkflowService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        nodes.put("s1", Map.of("id", "s1", "type", "STATE", "stateKey", "a", "valueTemplate", "a"));
        Map<String, Object> parallel = Map.of("id", "parallel1", "type", "PARALLEL",
                "branches", List.of("s1"), "joinNode", "join1");

        WorkflowRun run = new WorkflowRun();
        run.setId(12L);
        run.setTenantId(1L);
        WorkflowNodeRun nr = new WorkflowNodeRun();

        Method gate = WorkflowService.class.getDeclaredMethod("ensureParallelApprovals",
                Map.class, String.class, WorkflowRun.class, Map.class, WorkflowNodeRun.class, JwtPrincipal.class);
        gate.setAccessible(true);
        boolean proceed = (boolean) gate.invoke(service, parallel, "parallel1", run, nodes, nr,
                new JwtPrincipal(7L, 1L, false, List.of("OWNER"), List.of()));

        assertEquals(true, proceed);
    }
}
