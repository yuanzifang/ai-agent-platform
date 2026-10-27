package com.platform.conversation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamRegistryServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private StreamRegistryService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new StreamRegistryService(redis);
    }

    @Test
    void registerWritesRedisKey() {
        service.register(1L, disposable(new AtomicBoolean()));
        verify(valueOps).set(eq("chat:active:1"), anyString(), any(Duration.class));
    }

    @Test
    void stopDisposesLocalStream() {
        AtomicBoolean disposed = new AtomicBoolean();
        service.register(2L, disposable(disposed));
        assertTrue(service.stop(2L));
        assertTrue(disposed.get());
        verify(redis, never()).convertAndSend(eq(StreamRegistryService.STOP_CHANNEL), anyString());
    }

    @Test
    void stopBroadcastsWhenLocalMissing() {
        assertFalse(service.stop(3L));
        verify(redis).convertAndSend(StreamRegistryService.STOP_CHANNEL, "3");
    }

    @Test
    void unregisterReturnsWhetherLocalExisted() {
        service.register(4L, disposable(new AtomicBoolean()));
        when(valueOps.get("chat:active:4")).thenReturn(service.instanceId());
        assertTrue(service.unregister(4L));
        assertFalse(service.unregister(4L));
        verify(redis, org.mockito.Mockito.atLeastOnce()).delete("chat:active:4");
    }

    @Test
    void unregisterDoesNotDeleteAnotherInstancesKey() {
        service.register(5L, disposable(new AtomicBoolean()));
        when(valueOps.get("chat:active:5")).thenReturn("other-instance");
        assertTrue(service.unregister(5L));
        verify(redis, never()).delete("chat:active:5");
    }

    @Test
    void refreshExtendsOnlyOwnedKey() {
        when(valueOps.get("chat:active:6")).thenReturn(service.instanceId());
        service.refresh(6L);
        verify(redis).expire(eq("chat:active:6"), any(Duration.class));

        when(valueOps.get("chat:active:7")).thenReturn("other-instance");
        service.refresh(7L);
        verify(redis, never()).expire(eq("chat:active:7"), any(Duration.class));
    }

    private Disposable disposable(AtomicBoolean flag) {
        return new Disposable() {
            @Override
            public void dispose() {
                flag.set(true);
            }

            @Override
            public boolean isDisposed() {
                return flag.get();
            }
        };
    }
}
