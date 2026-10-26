package com.platform.deploy.service;

import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.common.exception.BusinessException;
import com.platform.deploy.entity.AgentRuntimeKey;
import com.platform.deploy.mapper.AgentRuntimeKeyMapper;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeKeyServiceTest {

    private final JwtPrincipal principal =
            new JwtPrincipal(7L, 9L, false, List.of("OWNER"), List.of("agent:edit"));
    private AgentRuntimeKeyMapper keyMapper;
    private AgentRegistryMapper agentMapper;
    private AuditService auditService;
    private AgentRuntimeKeyService service;

    @BeforeEach
    void setUp() {
        keyMapper = mock(AgentRuntimeKeyMapper.class);
        agentMapper = mock(AgentRegistryMapper.class);
        auditService = mock(AuditService.class);
        service = new AgentRuntimeKeyService(keyMapper, agentMapper, auditService);

        AgentRegistry agent = new AgentRegistry();
        agent.setId(1L);
        agent.setTenantId(9L);
        agent.setStatus("PUBLISHED");
        when(agentMapper.selectById(1L)).thenReturn(agent);
        when(keyMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            AgentRuntimeKey key = invocation.getArgument(0);
            key.setId(11L);
            return 1;
        }).when(keyMapper).insert(any(AgentRuntimeKey.class));
    }

    @Test
    void createReturnsPlaintextOnceAndStoresOnlyHash() {
        Map<String, Object> result = service.create(1L,
                Map.of("name", " 移动端 ", "dailyQuota", 100), principal);

        String rawKey = String.valueOf(result.get("apiKey"));
        assertTrue(rawKey.startsWith("sk-run-"));
        assertEquals(rawKey.substring(0, 12), result.get("keyPrefix"));

        ArgumentCaptor<AgentRuntimeKey> captor = ArgumentCaptor.forClass(AgentRuntimeKey.class);
        verify(keyMapper).insert(captor.capture());
        AgentRuntimeKey stored = captor.getValue();
        assertEquals("移动端", stored.getName());
        assertEquals(100, stored.getDailyQuota());
        assertTrue(stored.getKeyHash().matches("[0-9a-f]{64}"));
        assertNotEquals(rawKey, stored.getKeyHash());
        assertEquals("ENABLED", stored.getStatus());
        verify(auditService).record(7L, "runtime_key.create", "agent", "1",
                "SUCCESS", Map.of("keyId", 11L, "name", "移动端", "quota", 100));
    }

    @Test
    void createRejectsMoreThanTwentyKeys() {
        when(keyMapper.selectCount(any())).thenReturn(20L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(1L, Map.of("name", "mobile"), principal));

        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("20"));
    }

    @Test
    void createRejectsNegativeQuota() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create(1L, Map.of("name", "mobile", "dailyQuota", -1), principal));

        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("负数"));
    }

    @Test
    void updateRejectsUnknownStatus() {
        AgentRuntimeKey key = existingKey();
        when(keyMapper.selectById(11L)).thenReturn(key);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.update(1L, 11L, Map.of("status", "PAUSED"), principal));

        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("ENABLED/DISABLED"));
    }

    private AgentRuntimeKey existingKey() {
        AgentRuntimeKey key = new AgentRuntimeKey();
        key.setId(11L);
        key.setAgentId(1L);
        key.setTenantId(9L);
        key.setName("mobile");
        key.setKeyHash("a".repeat(64));
        key.setDailyQuota(0);
        key.setStatus("ENABLED");
        return key;
    }
}
