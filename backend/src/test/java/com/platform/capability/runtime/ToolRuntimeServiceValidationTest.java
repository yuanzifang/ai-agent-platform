package com.platform.capability.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
}
