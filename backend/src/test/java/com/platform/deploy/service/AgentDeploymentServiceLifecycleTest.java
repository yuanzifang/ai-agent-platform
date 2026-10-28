package com.platform.deploy.service;

import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.agent.mapper.AgentVersionMapper;
import com.platform.agent.service.ApiKeyService;
import com.platform.common.exception.BusinessException;
import com.platform.common.security.CryptoService;
import com.platform.deploy.entity.AgentDeployProfile;
import com.platform.deploy.entity.AgentDeployment;
import com.platform.deploy.entity.AgentDeploymentEvent;
import com.platform.deploy.mapper.AgentDeploymentEventMapper;
import com.platform.deploy.mapper.AgentDeploymentMapper;
import com.platform.deploy.runtime.ContainerRuntime;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.AuditService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentDeploymentServiceLifecycleTest {

    private AgentDeploymentMapper deploymentMapper;
    private AgentRuntimeKeyService runtimeKeyService;
    private CryptoService cryptoService;
    private AgentDeploymentEventMapper eventMapper;
    private AgentRegistryMapper agentMapper;
    private AgentVersionMapper versionMapper;
    private ApiKeyService apiKeyService;
    private AuditService auditService;
    private ContainerRuntime runtime;
    private AgentDeployProfileService deployProfileService;
    private SimpleMeterRegistry meterRegistry;
    private AgentDeploymentService service;
    private AgentDeployment deployment;
    private final JwtPrincipal principal = new JwtPrincipal(7L, 1L, false, List.of("OWNER"), List.of());

    @BeforeEach
    void setUp() {
        deploymentMapper = mock(AgentDeploymentMapper.class);
        runtimeKeyService = mock(AgentRuntimeKeyService.class);
        cryptoService = mock(CryptoService.class);
        eventMapper = mock(AgentDeploymentEventMapper.class);
        agentMapper = mock(AgentRegistryMapper.class);
        versionMapper = mock(AgentVersionMapper.class);
        apiKeyService = mock(ApiKeyService.class);
        auditService = mock(AuditService.class);
        runtime = mock(ContainerRuntime.class);
        deployProfileService = mock(AgentDeployProfileService.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new AgentDeploymentService(deploymentMapper, runtimeKeyService, cryptoService, eventMapper,
                agentMapper, versionMapper, apiKeyService, auditService, runtime,
                deployProfileService, meterRegistry);

        deployment = new AgentDeployment();
        deployment.setId(5L);
        deployment.setAgentId(1L);
        deployment.setTenantId(1L);
        deployment.setContainerName("aap-agent-1");
        deployment.setContainerId("cid-1");
        deployment.setHostPort(19001);
        deployment.setLbContainerName("aap-agent-1-lb");
        deployment.setLbContainerId("cid-lb");
        deployment.setLbHostPort(19003);
        deployment.setLbStatus("RUNNING");
        deployment.setLbEndpoint("http://test:19003/v1");
        deployment.setLbLocalEndpoint("http://localhost:19003/v1");
        deployment.setStatus("RUNNING");
        deployment.setReplicasJson("""
                [{"name":"aap-agent-1","containerId":"cid-1","hostPort":19001,"status":"RUNNING","endpoint":"http://test:19001/v1"},
                 {"name":"aap-agent-1-r2","containerId":"cid-2","hostPort":19002,"status":"RUNNING","endpoint":"http://test:19002/v1"}]
                """);
        when(deploymentMapper.selectOne(any())).thenReturn(deployment);
        when(deploymentMapper.updateById(any(AgentDeployment.class))).thenReturn(1);
    }

    @Test
    void startsAndStopsAllReplicas() {
        Map<String, Object> started = service.start(1L, principal);
        assertEquals("RUNNING", started.get("status"));
        assertEquals("http://test:19003/v1", started.get("lbEndpoint"));
        assertEquals(2, started.get("replicaCount"));
        verify(runtime).start("aap-agent-1");
        verify(runtime).start("aap-agent-1-r2");
        verify(runtime).start("aap-agent-1-lb");
        verify(eventMapper).insert(argThat((AgentDeploymentEvent e) -> "START".equals(e.getAction())));

        Map<String, Object> stopped = service.stop(1L, principal);
        assertEquals("STOPPED", stopped.get("status"));
        verify(runtime).stop("aap-agent-1");
        verify(runtime).stop("aap-agent-1-r2");
        verify(runtime).stop("aap-agent-1-lb");
        verify(eventMapper).insert(argThat((AgentDeploymentEvent e) -> "STOP".equals(e.getAction())));
    }

    @Test
    void removeCleansContainersKeyAndDeployment() {
        deployment.setApiKeyId(9L);

        service.remove(1L, principal);

        verify(runtime, org.mockito.Mockito.atLeastOnce()).remove("aap-agent-1");
        verify(runtime).remove("aap-agent-1-r2");
        verify(runtime).remove("aap-agent-1-lb");
        verify(apiKeyService).delete(1L, 9L, principal);
        verify(eventMapper).insert(argThat((AgentDeploymentEvent e) -> "REMOVE".equals(e.getAction())));
        verify(deploymentMapper).deleteById(5L);
        verify(auditService).record(7L, "agent.undeploy", "agent", "1", "SUCCESS", Map.of("containers", 2));
    }

    @Test
    void deploymentMetricsFollowLifecycle() {
        service.start(1L, principal);
        assertGauge("agent.deployment.running", 1.0);
        assertGauge("agent.deployment.replicas", 2.0);
        assertGauge("agent.deployment.ready_replicas", 2.0);
        assertGauge("agent.deployment.lb_running", 1.0);

        service.stop(1L, principal);
        assertGauge("agent.deployment.running", 0.0);
        assertGauge("agent.deployment.ready_replicas", 0.0);
        assertGauge("agent.deployment.lb_running", 0.0);
    }

    private void assertGauge(String name, double expected) {
        var gauge = meterRegistry.get(name).tags("agent_id", "1", "tenant_id", "1").gauge();
        assertEquals(expected, gauge.value());
    }

    @Test
    void allReplicaLogsIncludeReplicasAndLoadBalancer() {
        when(runtime.logs(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn("line");

        String logs = service.logs(1L, 20, true);

        assertTrue(logs.contains("===== aap-agent-1 (19001) ====="));
        assertTrue(logs.contains("===== aap-agent-1-r2 (19002) ====="));
        assertTrue(logs.contains("===== aap-agent-1-lb (19003) ====="));
        assertTrue(logs.contains("line"));
    }

    @Test
    void containerResourceLimitsAreApplied() {
        ReflectionTestUtils.setField(service, "cpuLimit", "1.5");
        ReflectionTestUtils.setField(service, "memoryLimit", "256m");
        ReflectionTestUtils.setField(service, "pidsLimit", 128);
        ReflectionTestUtils.setField(service, "logMaxSize", "10m");
        ReflectionTestUtils.setField(service, "logMaxFile", 3);

        assertEquals(List.of("--cpus", "1.5", "--memory", "256m",
                "--pids-limit", "128", "--security-opt", "no-new-privileges",
                "--log-driver", "json-file", "--log-opt", "max-size=10m", "--log-opt", "max-file=3"),
                service.containerLimits());

        ReflectionTestUtils.setField(service, "cpuLimit", "");
        ReflectionTestUtils.setField(service, "memoryLimit", "");
        ReflectionTestUtils.setField(service, "pidsLimit", 0);
        ReflectionTestUtils.setField(service, "logMaxSize", "");
        ReflectionTestUtils.setField(service, "logMaxFile", 0);
        assertEquals(List.of("--security-opt", "no-new-privileges"), service.containerLimits());
    }

    @Test
    void deployRequiresPublishedAgent() {
        when(runtime.available()).thenReturn(true);
        AgentRegistry agent = new AgentRegistry();
        agent.setId(1L);
        agent.setTenantId(1L);
        agent.setStatus("DRAFT");
        when(agentMapper.selectById(1L)).thenReturn(agent);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.deploy(1L, principal));

        assertEquals(400, error.getCode());
        assertEquals("请先发布 Agent，再部署到容器", error.getMessage());
        verify(runtime, never()).create(any());
    }

    @Test
    void multiReplicaRequiresGatewayRedis() {
        when(runtime.available()).thenReturn(true);
        AgentRegistry agent = new AgentRegistry();
        agent.setId(1L);
        agent.setTenantId(1L);
        agent.setStatus("PUBLISHED");
        when(agentMapper.selectById(1L)).thenReturn(agent);
        when(deploymentMapper.selectOne(any())).thenReturn(null);
        ReflectionTestUtils.setField(service, "deployRuntime", "gateway");
        ReflectionTestUtils.setField(service, "redisUrl", "");

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.deploy(1L, 2, principal));

        assertEquals(400, error.getCode());
        assertEquals("多副本部署需要 gateway 运行时并配置 agent.deploy.redis-url（共享限流/配额/用量）",
                error.getMessage());
        verify(runtime, never()).create(any());
    }

    @Test
    void deployProfileOverridesContainerLimits() {
        AgentDeployProfile profile = new AgentDeployProfile();
        profile.setCpuLimit("0.5");
        profile.setMemoryLimit("384m");
        profile.setPidsLimit(128);

        assertEquals(List.of("--cpus", "0.5", "--memory", "384m",
                        "--pids-limit", "128", "--security-opt", "no-new-privileges"),
                service.containerLimits(profile));
    }

    @Test
    void deployRejectsReplicasAboveProfileMaximum() {
        when(runtime.available()).thenReturn(true);
        AgentRegistry agent = new AgentRegistry();
        agent.setId(1L);
        agent.setTenantId(1L);
        agent.setStatus("PUBLISHED");
        when(agentMapper.selectById(1L)).thenReturn(agent);
        when(deploymentMapper.selectOne(any())).thenReturn(null);
        AgentDeployProfile profile = new AgentDeployProfile();
        profile.setId(9L);
        profile.setMaxReplicas(2);
        profile.setDefaultReplicas(1);
        when(deployProfileService.resolve(9L)).thenReturn(profile);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.deploy(1L, 3, 9L, principal));

        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("上限"));
        verify(runtime, never()).create(any());
    }
}
