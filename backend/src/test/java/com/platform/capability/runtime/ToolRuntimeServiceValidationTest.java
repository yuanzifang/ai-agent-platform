package com.platform.capability.runtime;

import com.platform.common.validation.ToolInputValidator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.capability.entity.ToolRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ai.tool.ToolCallback;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

class ToolRuntimeServiceValidationTest {

    private static final String SCHEMA = """
            {"type":"object","required":["amount"],
             "properties":{"amount":{"type":"integer","minimum":1}}}
            """;

    private ToolRuntimeService service() {
        ToolRuntimeService service = mock(ToolRuntimeService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "inputValidator", new ToolInputValidator(new ObjectMapper()));
        ReflectionTestUtils.setField(service, "maxInputValidationAttempts", 2);
        ReflectionTestUtils.setField(service, "idempotencyKey", new ThreadLocal<String>());
        return service;
    }

    @Test
    void returnsRetryableErrorThenFinalErrorAtLimit() throws Exception {
        ToolRuntimeService service = service();
        ToolCallCollector collector = new ToolCallCollector();
        ObjectMapper mapper = new ObjectMapper();

        String first = service.validateToolInput("t", "T", "API", collector, "{\"amount\":\"x\"}",
                SCHEMA, Map.of("amount", "x"));
        JsonNode firstJson = mapper.readTree(first);
        assertTrue(firstJson.path("retryable").asBoolean());
        assertEquals(1, firstJson.path("attempt").asInt());
        assertFalse(firstJson.path("violations").isEmpty());

        String second = service.validateToolInput("t", "T", "API", collector, "{\"amount\":\"x\"}",
                SCHEMA, Map.of("amount", "x"));
        JsonNode secondJson = mapper.readTree(second);
        assertFalse(secondJson.path("retryable").asBoolean());
        assertEquals(2, secondJson.path("attempt").asInt());
    }

    @Test
    void validInputReturnsNull() {
        ToolRuntimeService service = service();
        ToolCallCollector collector = new ToolCallCollector();
        assertNull(service.validateToolInput("t", "T", "API", collector, "{\"amount\":1}",
                SCHEMA, Map.of("amount", 1)));
    }

    @Test
    void apiCallbackReturnsRetryableThenExecutesCorrectedInput() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/echo", exchange -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ToolRuntimeService service = service();
            ReflectionTestUtils.setField(service, "httpClient", HttpClient.newHttpClient());
            ToolRegistry tool = new ToolRegistry();
            tool.setId(1L);
            tool.setToolKey("echo");
            tool.setName("Echo");
            tool.setToolType("API");
            tool.setApiConfig("""
                    {"url":"http://127.0.0.1:%d/echo","method":"GET",
                     "params":[{"name":"amount","type":"integer","in":"query","required":true}],
                     "responsePath":"ok"}
                    """.formatted(server.getAddress().getPort()));
            ToolCallCollector collector = new ToolCallCollector();
            Method factory = ToolRuntimeService.class.getDeclaredMethod("apiCallback",
                    ToolRegistry.class, ToolCallCollector.class, Long.class, boolean.class);
            factory.setAccessible(true);
            ToolCallback callback = (ToolCallback) factory.invoke(service, tool, collector, null, false);

            String first = callback.call("{\"amount\":\"x\"}");
            assertTrue(new ObjectMapper().readTree(first).path("retryable").asBoolean());

            String second = callback.call("{\"amount\":2}");
            assertTrue(second.contains("true"), second);
        } finally {
            server.stop(0);
        }
    }
}
