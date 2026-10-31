package com.platform.deploy.service;

import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.agent.mapper.IntegrationApiKeyMapper;
import com.platform.common.exception.BusinessException;
import com.platform.common.security.CryptoService;
import com.platform.deploy.entity.AgentDeployment;
import com.platform.deploy.entity.AgentRuntimeKey;
import com.platform.deploy.mapper.AgentDeploymentMapper;
import com.platform.deploy.service.AgentRuntimeKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRuntimeGatewayServiceTest {

    private AgentDeploymentMapper deploymentMapper;
    private AgentRuntimeKeyService runtimeKeyService;
    private CryptoService cryptoService;
    private AgentRuntimeGatewayService service;
    private AgentDeployment deployment;

    @BeforeEach
    void setUp() {
        deploymentMapper = mock(AgentDeploymentMapper.class);
        runtimeKeyService = mock(AgentRuntimeKeyService.class);
        cryptoService = mock(CryptoService.class);
        service = new AgentRuntimeGatewayService(deploymentMapper, runtimeKeyService,
                mock(AgentRegistryMapper.class), mock(IntegrationApiKeyMapper.class),
                cryptoService, null);
        ReflectionTestUtils.setField(service, "rateLimitPerMin", 1);
        ReflectionTestUtils.setField(service, "rateLimitBurst", 0);
        ReflectionTestUtils.setField(service, "callerQuotaDaily", 0);

        deployment = new AgentDeployment();
        deployment.setAgentId(1L);
        deployment.setTenantId(1L);
        deployment.setStatus("RUNNING");
        deployment.setRuntimeToken("encrypted-token");
        deployment.setApiKeyId(9L);
        when(deploymentMapper.findByAgentIdIgnore(1L)).thenReturn(deployment);
        when(cryptoService.decrypt("encrypted-token")).thenReturn("runtime-token");
    }

    @Test
    void authenticatesDeploymentToken() {
        AgentRuntimeGatewayService.Caller caller = service.authenticate(1L, "Bearer runtime-token", true);

        assertEquals("default", caller.id());
        assertEquals(1L, caller.tenantId());
    }

    @Test
    void authenticatesCallerKeyAndUsesHashPrefixAsCallerId() {
        AgentRuntimeKey key = new AgentRuntimeKey();
        key.setId(7L);
        key.setName("partner");
        key.setKeyHash(sha256("caller-key"));
        key.setDailyQuota(3);
        when(runtimeKeyService.enabledKeys(1L)).thenReturn(List.of(key));

        AgentRuntimeGatewayService.Caller caller = service.authenticate(1L, "Bearer caller-key", true);

        assertEquals(sha256("caller-key").substring(0, 12), caller.id());
        assertEquals("partner", caller.name());
        assertEquals(3, caller.dailyQuota());
    }

    @Test
    void rejectsInvalidBearerAndStoppedDeployment() {
        assertThrows(BusinessException.class,
                () -> service.authenticate(1L, "Bearer wrong", true));

        deployment.setStatus("STOPPED");
        BusinessException invalid = assertThrows(BusinessException.class,
                () -> service.authenticate(1L, "Bearer wrong", true));
        assertEquals(401, invalid.getCode());
        BusinessException stopped = assertThrows(BusinessException.class,
                () -> service.authenticate(1L, "Bearer runtime-token", true));
        assertEquals(409, stopped.getCode());
    }

    @Test
    void appliesPerCallerRateLimitAndQuota() {
        AgentRuntimeGatewayService.Caller caller =
                new AgentRuntimeGatewayService.Caller("caller-a", "caller-a", 0, 1L);
        service.beforeRequest(1L, caller, false);
        BusinessException rate = assertThrows(BusinessException.class,
                () -> service.beforeRequest(1L, caller, false));
        assertEquals(429, rate.getCode());

        ReflectionTestUtils.setField(service, "rateLimitPerMin", 0);
        AgentRuntimeGatewayService.Caller quotaCaller =
                new AgentRuntimeGatewayService.Caller("caller-b", "caller-b", 1, 1L);
        service.beforeRequest(1L, quotaCaller, false);
        BusinessException quota = assertThrows(BusinessException.class,
                () -> service.beforeRequest(1L, quotaCaller, false));
        assertEquals(429, quota.getCode());
    }

    @Test
    void reportsLocalUsageWithoutRedis() {
        ReflectionTestUtils.setField(service, "rateLimitPerMin", 0);
        service.beforeRequest(1L,
                new AgentRuntimeGatewayService.Caller("default", "deployment token", 0, 1L), true);

        Map<String, Object> result = service.stats(1L);

        assertEquals("memory", result.get("store"));
        assertEquals("platform", result.get("runtime"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> callers = (List<Map<String, Object>>) result.get("callers");
        assertEquals(1, callers.size());
        assertEquals("default", callers.get(0).get("caller"));
        assertEquals(1L, callers.get(0).get("requests"));
        assertEquals(1L, callers.get(0).get("streams"));
    }

    @Test
    void statsUsesPlatformRuntimeName() {
        Map<String, Object> result = service.stats(1L);

        assertEquals("platform", result.get("runtime"));
        assertTrue(result.containsKey("rateLimitPerMin"));
    }

    private String sha256(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
