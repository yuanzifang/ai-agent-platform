package com.platform.conversation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatLiveStateServiceTest {

    private ValueOperations<String, String> valueOps;
    private ChatLiveStateService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new ChatLiveStateService(redis, new ObjectMapper());
    }

    @Test
    void heartbeatIsTraceScopedAndFreshWithinThreshold() {
        service.touch(1L, "trace-a");
        verify(valueOps).set(eq("chat:live:hb:1:trace-a"), anyString(), any(Duration.class));

        when(valueOps.get("chat:live:hb:1:trace-a"))
                .thenReturn(String.valueOf(System.currentTimeMillis()));
        assertTrue(service.heartbeatFresh(1L, "trace-a", 5000));
        assertFalse(service.heartbeatFresh(1L, "trace-b", 5000));
    }

    @Test
    void staleOrMissingHeartbeatIsNotFresh() {
        when(valueOps.get("chat:live:hb:2:trace-old"))
                .thenReturn(String.valueOf(System.currentTimeMillis() - 60_000));
        assertFalse(service.heartbeatFresh(2L, "trace-old", 5000));
        assertFalse(service.heartbeatFresh(2L, "missing", 5000));
    }
}
