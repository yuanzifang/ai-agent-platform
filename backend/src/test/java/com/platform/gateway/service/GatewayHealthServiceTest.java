package com.platform.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.security.NetworkPolicyService;
import com.platform.gateway.entity.GatewayRoute;
import com.platform.gateway.mapper.GatewayRouteMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class GatewayHealthServiceTest {

    private final GatewayHealthService service = new GatewayHealthService(
            mock(GatewayRouteMapper.class), mock(StringRedisTemplate.class),
            mock(NetworkPolicyService.class), new ObjectMapper());

    @Test
    void joinsHealthUrlWithoutDuplicateSlash() {
        assertEquals("http://127.0.0.1:8080/health",
                service.healthUrl("http://127.0.0.1:8080/", "/health"));
        assertEquals("http://127.0.0.1:8080/api/health",
                service.healthUrl("http://127.0.0.1:8080/api", "/health"));
    }

    @Test
    void treats4xxAsReachableAnd5xxAsDown() {
        assertTrue(service.healthy(200));
        assertTrue(service.healthy(301));
        assertTrue(service.healthy(401));
        assertTrue(service.healthy(404));
        assertFalse(service.healthy(500));
        assertFalse(service.healthy(503));
    }

    @Test
    void defaultPathIsRootAndAddsLeadingSlash() {
        GatewayRoute route = new GatewayRoute();
        assertEquals("/", service.healthPath(route));
        route.setHealthPath("actuator/health");
        assertEquals("/actuator/health", service.healthPath(route));
        route.setHealthPath(" /ready ");
        assertEquals("/ready", service.healthPath(route));
    }
}
