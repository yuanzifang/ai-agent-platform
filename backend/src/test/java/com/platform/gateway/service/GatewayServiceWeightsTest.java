package com.platform.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.validation.ToolInputValidator;
import com.platform.common.security.CryptoService;
import com.platform.common.security.NetworkPolicyService;
import com.platform.gateway.entity.GatewayRoute;
import com.platform.gateway.mapper.GatewayRequestLogMapper;
import com.platform.gateway.mapper.GatewayRouteMapper;
import com.platform.system.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class GatewayServiceWeightsTest {

    private final GatewayService service = new GatewayService(
            mock(GatewayRouteMapper.class), mock(GatewayRequestLogMapper.class),
            mock(NetworkPolicyService.class), mock(StringRedisTemplate.class),
            mock(AuditService.class), mock(GatewayApiKeyService.class),
            new ObjectMapper(), mock(GatewayHealthService.class), mock(CryptoService.class),
            mock(ToolInputValidator.class));

    @Test
    void keepsTargetFirstWhenFallbackWeightIsZero() {
        GatewayRoute route = route(100, 0);
        assertEquals(List.of("http://a", "http://b"), service.weightedTargets(route));
    }

    @Test
    void choosesWeightedFallbackFirstWhenTargetIsZero() {
        GatewayRoute route = route(0, 100);
        assertEquals(List.of("http://b", "http://a"), service.weightedTargets(route));
    }

    @Test
    void keepsOriginalOrderWhenAllWeightsAreZero() {
        GatewayRoute route = route(0, 0);
        assertEquals(List.of("http://a", "http://b"), service.weightedTargets(route));
    }

    private GatewayRoute route(int targetWeight, int fallbackWeight) {
        GatewayRoute route = new GatewayRoute();
        route.setId(1L);
        route.setTargetUrl("http://a");
        route.setFallbackUrl("http://b");
        route.setUpstreamWeights("{\"http://a\":" + targetWeight + ",\"http://b\":" + fallbackWeight + "}");
        return route;
    }
}
