package com.platform.deploy.controller;

import com.platform.agent.controller.OpenApiController;
import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.entity.IntegrationApiKey;
import com.platform.common.exception.BusinessException;
import com.platform.deploy.service.AgentRuntimeGatewayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeGatewayControllerTest {

    private AgentRuntimeGatewayService runtimeGatewayService;
    private OpenApiController openApiController;
    private AgentRuntimeGatewayController controller;

    @BeforeEach
    void setUp() {
        runtimeGatewayService = mock(AgentRuntimeGatewayService.class);
        openApiController = mock(OpenApiController.class);
        controller = new AgentRuntimeGatewayController(runtimeGatewayService, openApiController);
    }

    @Test
    void modelsAuthenticatesChecksAgentAndRecordsUsage() {
        when(runtimeGatewayService.authenticate(1L, "Bearer token", true))
                .thenReturn(new AgentRuntimeGatewayService.Caller("default", "deployment token", 0, 1L));
        when(runtimeGatewayService.agent(1L)).thenReturn(new AgentRegistry());

        Map<String, Object> result = controller.models(1L, "Bearer token");

        assertEquals("list", result.get("object"));
        verify(runtimeGatewayService).beforeRequest(eq(1L), any(), eq(false));
    }

    @Test
    void chatReusesOpenApiPipelineWithTenantKey() {
        AgentRuntimeGatewayService.Caller caller =
                new AgentRuntimeGatewayService.Caller("default", "deployment token", 0, 1L);
        AgentRegistry agent = new AgentRegistry();
        IntegrationApiKey key = new IntegrationApiKey();
        key.setId(9L);
        Map<String, Object> expected = Map.of("id", "chatcmpl-1");
        when(runtimeGatewayService.authenticate(1L, "Bearer token", true)).thenReturn(caller);
        when(runtimeGatewayService.agent(1L)).thenReturn(agent);
        when(runtimeGatewayService.integrationKey(1L)).thenReturn(key);
        when(openApiController.completeFor(eq(key), eq(agent), any(), eq("gray-1"), any()))
                .thenReturn(expected);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        Object result = controller.chatCompletions(1L, "Bearer token", "gray-1",
                Map.of("messages", List.of(Map.of("role", "user", "content", "hi"))), request, response);

        assertEquals(expected, result);
        verify(runtimeGatewayService).beforeRequest(1L, caller, false);
    }

    @Test
    void statsOnlyAllowsDeploymentToken() {
        when(runtimeGatewayService.authenticate(1L, "Bearer caller", false))
                .thenReturn(new AgentRuntimeGatewayService.Caller("abc123", "partner", 1, 1L));

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.stats(1L, "Bearer caller"));

        assertEquals(403, error.getCode());
        verify(runtimeGatewayService, never()).stats(1L);
    }

    @Test
    void businessErrorsUseOpenAiShape() {
        ResponseEntity<Map<String, Object>> response = controller.runtimeError(
                new BusinessException(429, "too many requests"));

        assertEquals(429, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
        assertEquals("rate_limit_error", error.get("type"));
        assertEquals("too many requests", error.get("message"));
    }
}
