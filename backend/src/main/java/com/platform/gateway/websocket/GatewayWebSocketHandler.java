package com.platform.gateway.websocket;

import com.platform.common.context.TenantContext;
import com.platform.gateway.entity.GatewayApiKey;
import com.platform.gateway.entity.GatewayRequestLog;
import com.platform.gateway.entity.GatewayRoute;
import com.platform.gateway.mapper.GatewayRequestLogMapper;
import com.platform.gateway.mapper.GatewayRouteMapper;
import com.platform.gateway.service.GatewayApiKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.scheduling.annotation.Scheduled;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网关 WebSocket 最小代理：X-Gateway-Key（或 ?key=）鉴权，双向文本/二进制帧转发。
 * 路由 targetUrl 使用 http(s)，运行时转换为 ws(s)；额外上游暂按 fallback 使用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayWebSocketHandler extends TextWebSocketHandler {

    private static final String PATH_PREFIX = "/api/v1/gateway/ws/";

    private final GatewayRouteMapper routeMapper;
    private final GatewayApiKeyService apiKeyService;
    private final GatewayRequestLogMapper logMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, WebSocket> upstreams = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> lastActivity = new ConcurrentHashMap<>();
    private final Map<String, Long> upstreamPongAt = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPingAt = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Value("${gateway.ws.heartbeat-ms:30000}")
    private long heartbeatMs = 30000;
    @org.springframework.beans.factory.annotation.Value("${gateway.ws.idle-timeout-ms:90000}")
    private long idleTimeoutMs = 90000;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String routeKey = routeKey(session);
        GatewayRoute route = routeKey == null ? null : routeMapper.findByRouteKeyIgnore(routeKey);
        if (route == null || route.getEnabled() == null || route.getEnabled() != 1) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("route unavailable"));
            return;
        }
        String provided = providedKey(session);
        GatewayApiKey caller = provided == null ? null : apiKeyService.matchEnabledKey(provided);
        if (caller != null && !caller.getRouteId().equals(route.getId())) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("key mismatch"));
            return;
        }
        if (caller != null && !apiKeyService.allowByKey(caller)) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("rate limited"));
            return;
        }
        if (caller == null && (provided == null
                || !apiKeyService.sha256(provided).equals(route.getApiKeyHash()))) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("invalid key"));
            return;
        }
        if (caller != null) apiKeyService.markUsed(caller);

        String target = websocketUrl(route, session);
        try {
            WebSocket upstream = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(target), new UpstreamListener(session))
                    .get(15, java.util.concurrent.TimeUnit.SECONDS);
            upstreams.put(session.getId(), upstream);
            sessions.put(session.getId(), session);
            lastActivity.put(session.getId(), System.currentTimeMillis());
            upstreamPongAt.put(session.getId(), System.currentTimeMillis());
            lastPingAt.put(session.getId(), System.currentTimeMillis());
            session.getAttributes().put("routeId", route.getId());
            session.getAttributes().put("upstream", upstream);
            writeLog(route, session, 101, null);
        } catch (Exception e) {
            session.close(CloseStatus.SERVER_ERROR.withReason("upstream unavailable"));
            writeLog(route, session, 502, e.getMessage());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        WebSocket upstream = upstreams.get(session.getId());
        if (upstream == null) {
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }
        lastActivity.put(session.getId(), System.currentTimeMillis());
        upstream.sendText(message.getPayload(), true).get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        try {
            WebSocket upstream = upstreams.get(session.getId());
            if (upstream == null) {
                session.close(CloseStatus.SERVER_ERROR);
                return;
            }
            lastActivity.put(session.getId(), System.currentTimeMillis());
            ByteBuffer payload = message.getPayload().duplicate();
            upstream.sendBinary(payload, true).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            try {
                if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
            } catch (Exception ignored) {
                // session already closed
            }
        }
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        lastActivity.put(session.getId(), System.currentTimeMillis());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        WebSocket upstream = upstreams.remove(session.getId());
        sessions.remove(session.getId());
        lastActivity.remove(session.getId());
        upstreamPongAt.remove(session.getId());
        lastPingAt.remove(session.getId());
        if (upstream != null) upstream.abort();
    }

    /** 定时 Ping 客户端/上游；长时间无 Pong 或客户端空闲则关闭，防止半开连接堆积。 */
    @Scheduled(fixedDelayString = "${gateway.ws.heartbeat-scan-ms:15000}")
    public void heartbeat() {
        long now = System.currentTimeMillis();
        long idle = Math.max(30_000L, idleTimeoutMs);
        for (Map.Entry<String, WebSocketSession> entry : sessions.entrySet()) {
            String id = entry.getKey();
            WebSocketSession session = entry.getValue();
            if (!session.isOpen()) {
                try { session.close(); } catch (Exception ignored) { }
                sessions.remove(id);
                continue;
            }
            if (now - lastActivity.getOrDefault(id, now) > idle
                    || now - upstreamPongAt.getOrDefault(id, now) > idle) {
                try { session.close(CloseStatus.SESSION_NOT_RELIABLE.withReason("heartbeat timeout")); }
                catch (Exception ignored) { }
                continue;
            }
            if (now - lastPingAt.getOrDefault(id, now) >= heartbeatMs) {
                lastPingAt.put(id, now);
                try {
                    session.sendMessage(new PingMessage());
                } catch (Exception e) {
                    try { session.close(); } catch (Exception ignored) { }
                }
                WebSocket upstream = upstreams.get(id);
                if (upstream != null) {
                    try {
                        upstream.sendPing(ByteBuffer.allocate(0));
                    } catch (Exception e) {
                        try { session.close(CloseStatus.SERVER_ERROR); } catch (Exception ignored) { }
                    }
                }
            }
        }
    }

    private String routeKey(WebSocketSession session) {
        String path = session.getUri() == null ? "" : session.getUri().getPath();
        if (!path.startsWith(PATH_PREFIX)) return null;
        String key = path.substring(PATH_PREFIX.length());
        int slash = key.indexOf('/');
        return slash >= 0 ? key.substring(0, slash) : key;
    }

    private String providedKey(WebSocketSession session) {
        String header = session.getHandshakeHeaders().getFirst("X-Gateway-Key");
        if (header != null && !header.isBlank()) return header;
        String query = session.getUri() == null ? null : session.getUri().getQuery();
        if (query == null) return null;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && "key".equals(kv[0])) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private String websocketUrl(GatewayRoute route, WebSocketSession session) {
        String base = route.getTargetUrl().replaceAll("/+$", "");
        String scheme = base.startsWith("https://") ? "wss://" : "ws://";
        base = base.replaceFirst("^https?://", "");
        String path = session.getUri() == null ? "" : session.getUri().getPath();
        String suffix = path.startsWith(PATH_PREFIX + route.getRouteKey())
                ? path.substring((PATH_PREFIX + route.getRouteKey()).length()) : "/";
        if (suffix.isBlank()) suffix = "/";
        if (!suffix.startsWith("/")) suffix = "/" + suffix;
        String query = session.getUri() == null ? null : session.getUri().getQuery();
        if (query != null) {
            query = java.util.Arrays.stream(query.split("&"))
                    .filter(p -> !p.startsWith("key=")).reduce((a, b) -> a + "&" + b).orElse("");
        }
        return scheme + base + suffix + (query == null || query.isBlank() ? "" : "?" + query);
    }

    private void writeLog(GatewayRoute route, WebSocketSession session, int status, String error) {
        try {
            TenantContext.runWith(route.getTenantId(), () -> {
                GatewayRequestLog row = new GatewayRequestLog();
                row.setTenantId(route.getTenantId());
                row.setRouteId(route.getId());
                row.setMethod("WS");
                row.setPath(session.getUri() == null ? "" : session.getUri().getPath());
                row.setStatus(status);
                row.setLatencyMs(0L);
                row.setError(error == null ? null : error.substring(0, Math.min(512, error.length())));
                row.setCreatedAt(LocalDateTime.now());
                logMapper.insert(row);
                return null;
            });
        } catch (Exception e) {
            log.debug("WS gateway log failed: {}", e.getMessage());
        }
    }

    private class UpstreamListener implements WebSocket.Listener {
        private final WebSocketSession session;
        private final StringBuilder text = new StringBuilder();

        UpstreamListener(WebSocketSession session) {
            this.session = session;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                try {
                    if (session.isOpen()) session.sendMessage(new TextMessage(text.toString()));
                } catch (Exception e) {
                    log.debug("WS downstream send failed: {}", e.getMessage());
                } finally {
                    text.setLength(0);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            try {
                if (session.isOpen()) {
                    byte[] bytes = new byte[data.remaining()];
                    data.get(bytes);
                    session.sendMessage(new BinaryMessage(bytes));
                }
            } catch (Exception e) {
                log.debug("WS downstream binary send failed: {}", e.getMessage());
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            upstreamPongAt.put(session.getId(), System.currentTimeMillis());
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            try {
                if (session.isOpen()) session.close(new CloseStatus(statusCode, reason));
            } catch (Exception ignored) {
                // session already closed
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            try {
                if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
            } catch (Exception ignored) {
                // session already closed
            }
        }
    }
}
