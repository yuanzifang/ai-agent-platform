package com.platform.common.exception;

import com.platform.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 5xx 对外统一脱敏，避免底层 HTTP/堆栈细节泄露。 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(
            new com.platform.system.service.AlertWebhookService(new com.fasterxml.jackson.databind.ObjectMapper()));

    @Test
    void business500IsSanitized() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(
                new BusinessException(500, "HTTP 401：{\"error\":\"invalid token secret\"}"));
        assertEquals(500, response.getStatusCode().value());
        String body = response.getBody() == null ? "" : response.getBody().message();
        assertFalse(body.contains("HTTP 401"), body);
        assertFalse(body.contains("secret"), body);
        assertTrue(body.contains("系统繁忙"), body);
    }

    @Test
    void business400KeepsUserMessage() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(
                new BusinessException(400, "名称不能为空"));
        assertEquals(400, response.getStatusCode().value());
        assertEquals("名称不能为空", response.getBody().message());
    }
}
