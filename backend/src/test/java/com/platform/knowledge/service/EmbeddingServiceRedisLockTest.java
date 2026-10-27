package com.platform.knowledge.service;

import com.platform.billing.service.QuotaService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingServiceRedisLockTest {

    @Test
    void fallsBackToLocalWhenAnotherInstanceHoldsLock() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            requests.incrementAndGet();
            byte[] response = "{\"data\":[{\"index\":0,\"embedding\":[0.5,0.25]}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            EmbeddingService service = service(server);
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> ops = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(ops);
            when(ops.get(anyString())).thenReturn(null);
            when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
            ReflectionTestUtils.setField(service, "redis", redis);
            ReflectionTestUtils.setField(service, "redisLockWaitMs", 20L);

            float[] result = service.embedQuery("cold-query", "hosted-embed");

            assertEquals(2, result.length);
            assertEquals(1, requests.get(), "等待锁超时后应回退本地计算一次");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesSharedRedisCacheWithoutCallingUpstream() {
        EmbeddingService service = new EmbeddingService(props(), mock(QuotaService.class));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(encode(1.0f, 2.0f));
        ReflectionTestUtils.setField(service, "redis", redis);

        float[] result = service.embedQuery("cached-query", "hosted-embed");

        assertEquals(2, result.length);
        assertEquals(1.0f, result[0]);
        assertEquals(2.0f, result[1]);
    }

    private static EmbeddingService service(HttpServer server) {
        EmbeddingProperties props = props();
        props.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
        return new EmbeddingService(props, mock(QuotaService.class));
    }

    private static EmbeddingProperties props() {
        EmbeddingProperties props = new EmbeddingProperties();
        props.setProvider("openai");
        props.setModel("hosted-embed");
        props.setDimensions(2);
        props.setAvailable(Map.of("hosted-embed", 2));
        return props;
    }

    private static String encode(float... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Float.BYTES);
        for (float value : values) buffer.putFloat(value);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    @Test
    void acquiresLockAndPublishesSharedCache() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            requests.incrementAndGet();
            byte[] response = "{\"data\":[{\"index\":0,\"embedding\":[0.5,0.25]}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            EmbeddingService service = service(server);
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> ops = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(ops);
            when(ops.get(anyString())).thenReturn(null);
            when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
            ReflectionTestUtils.setField(service, "redis", redis);

            float[] result = service.embedQuery("lock-query", "hosted-embed");

            assertEquals(2, result.length);
            assertEquals(1, requests.get());
            verify(ops).set(anyString(), anyString(), any(Duration.class));
            verify(redis).execute(any(DefaultRedisScript.class), anyList(), anyString());
        } finally {
            server.stop(0);
        }
    }
}
