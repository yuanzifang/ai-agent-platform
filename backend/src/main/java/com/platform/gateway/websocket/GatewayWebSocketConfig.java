package com.platform.gateway.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** 网关 WebSocket 入口；/api/v1/gateway/ws/{routeKey}?key=... */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class GatewayWebSocketConfig implements WebSocketConfigurer {

    private final GatewayWebSocketHandler handler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/gateway/ws/**")
                .setAllowedOriginPatterns("*");
    }
}
