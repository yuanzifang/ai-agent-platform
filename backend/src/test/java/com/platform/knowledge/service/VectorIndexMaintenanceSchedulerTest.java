package com.platform.knowledge.service;

import com.platform.system.mapper.SysTenantMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VectorIndexMaintenanceSchedulerTest {

    @SuppressWarnings("unchecked")
    @Test
    void skipsWhenAnotherInstanceHoldsTheLock() {
        SysTenantMapper tenantMapper = mock(SysTenantMapper.class);
        VectorRepository vectorRepository = mock(VectorRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(operations);
        when(operations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        new VectorIndexMaintenanceScheduler(tenantMapper, vectorRepository, redis).run();

        verifyNoInteractions(tenantMapper, vectorRepository);
    }
}
