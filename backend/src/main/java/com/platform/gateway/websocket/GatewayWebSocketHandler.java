package com.platform.gateway.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.context.TenantContext;
import com.platform.gateway.entity.GatewayApiKey;
import com.platform.gateway.entity.GatewayRequestLog;
import com.platform.gateway.entity.GatewayRoute;
import com.platform.gateway.mapper.GatewayRequestLogMapper;
import com.platform.gateway.mapper.GatewayRouteMapper;
import com.platform.common.security.CryptoService;
import com.platform.gateway.service.GatewayApiKeyService;
import com.platform.gateway.service.GatewaySignature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final CryptoService cryptoService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, WebSocket> upstreams = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, GatewayRoute> routes = new ConcurrentHashMap<>();
    private final Map<String, Long> lastActivity = new ConcurrentHashMap<>();
    private final Map<String, Long> upstreamPongAt = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPingAt = new ConcurrentHashMap<>();
    private final Map<Long, AtomicInteger> activeByRoute = new ConcurrentHashMap<>();

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
        String waf = com.platform.gateway.service.GatewayWaf.check("GET",
                session.getUri() == null ? null : session.getUri().getPath(),
                session.getUri() == null ? null : session.getUri().getQuery());
        if (waf != null) {
            writeLog(route, session, 400, "waf: " + waf);
            session.close(CloseStatus.POLICY_VIOLATION.withReason("waf"));
            return;
        }
        String signatureError = verifySignature(route, session);
        if (signatureError != null) {
            int status = "replay".equals(signatureError) ? 409 : 401;
            writeLog(route, session, status, "signature: " + signatureError);
            session.close(CloseStatus.POLICY_VIOLATION.withReason("signature"));
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
        if (caller != null && !apiKeyService.allowDaily(caller)) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("daily quota"));
            return;
        }
        if (caller == null && (provided == null
                || !apiKeyService.sha256(provided).equals(route.getApiKeyHash()))) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("invalid key"));
            return;
        }
        if (caller != null) apiKeyService.markUsed(caller);

        int maxConnections = route.getWsMaxConnections() == null ? 100 : route.getWsMaxConnections();
        if (!acquire(route.getId(), maxConnections)) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("connection limit"));
            writeLog(route, session, 429, "websocket connection limit");
            return;
        }
        try {
            WebSocket upstream = connectUpstream(route, session);
            upstreams.put(session.getId(), upstream);
            sessions.put(session.getId(), session);
            routes.put(session.getId(), route);
            lastActivity.put(session.getId(), System.currentTimeMillis());
            upstreamPongAt.put(session.getId(), System.currentTimeMillis());
            lastPingAt.put(session.getId(), System.currentTimeMillis());
            session.getAttributes().put("routeId", route.getId());
            session.getAttributes().put("upstream", upstream);
            writeLog(route, session, 101, null);
        } catch (Exception e) {
            release(route.getId());
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
        routes.remove(session.getId());
        lastActivity.remove(session.getId());
        upstreamPongAt.remove(session.getId());
        lastPingAt.remove(session.getId());
        Object routeId = session.getAttributes().get("routeId");
        if (routeId instanceof Long id) release(id);
        if (upstream != null) upstream.abort();
    }

    /** Redis 全局计数；Redis 故障时退回本实例计数，优先保证网关可用。 */
    private boolean acquire(Long routeId, int maxConnections) {
        if (maxConnections <= 0) return true;
        try {
            String key = activeKey(routeId);
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) redis.expire(key, Duration.ofHours(2));
            if (count != null && count > maxConnections) {
                redis.opsForValue().decrement(key);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("WS 全局连接计数失败，退回实例计数 route={}: {}", routeId, e.getMessage());
            AtomicInteger active = activeByRoute.computeIfAbsent(routeId, k -> new AtomicInteger());
            int current = active.incrementAndGet();
            if (current <= maxConnections) return true;
            active.decrementAndGet();
            return false;
        }
    }

    private void release(Long routeId) {
        try {
            String key = activeKey(routeId);
            if (Boolean.TRUE.equals(redis.hasKey(key))) {
                Long count = redis.opsForValue().decrement(key);
                if (count != null && count <= 0) redis.delete(key);
            }
        } catch (Exception e) {
            log.debug("WS 全局连接计数释放失败 route={}: {}", routeId, e.getMessage());
        }
        activeByRoute.computeIfPresent(routeId, (id, count) -> count.decrementAndGet() <= 0 ? null : count);
    }

    private String activeKey(Long routeId) {
        return "gateway:ws:active:" + routeId;
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

    private String verifySignature(GatewayRoute route, WebSocketSession session) {
        if (route.getSigningSecret() == null || route.getSigningSecret().isBlank()) return null;
        String secret;
        try {
            secret = cryptoService.decrypt(route.getSigningSecret());
        } catch (Exception e) {
            return "signing key invalid";
        }
        String query = session.getUri() == null ? "" : session.getUri().getQuery();
        Map<String, String> params = queryParams(query);
        String timestamp = headerOrParam(session, "X-Gateway-Timestamp", params, "timestamp");
        String nonce = headerOrParam(session, "X-Gateway-Nonce", params, "nonce");
        String signature = headerOrParam(session, "X-Gateway-Signature", params, "signature");
        String canonicalQuery = query == null ? "" : java.util.Arrays.stream(query.split("&"))
                .filter(p -> !p.startsWith("signature="))
                .reduce((a, b) -> a + "&" + b).orElse("");
        String error = GatewaySignature.verifyHeader(secret, "GET",
                session.getUri() == null ? null : session.getUri().getPath(), canonicalQuery,
                timestamp, nonce, new byte[0], signature);
        if (error != null) return error;
        try {
            String key = "gateway:nonce:" + route.getId() + ":" + nonce;
            Boolean fresh = redis.opsForValue().setIfAbsent(key, "1", GatewaySignature.nonceTtl());
            if (!Boolean.TRUE.equals(fresh)) return "replay";
        } catch (Exception e) {
            log.warn("WS nonce 校验失败（已放行）route={}: {}", route.getId(), e.getMessage());
        }
        return null;
    }

    private String headerOrParam(WebSocketSession session, String header, Map<String, String> params, String name) {
        String value = session.getHandshakeHeaders().getFirst(header);
        return value == null || value.isBlank() ? params.get(name) : value;
    }

    private Map<String, String> queryParams(String query) {
        Map<String, String> params = new java.util.HashMap<>();
        if (query == null || query.isBlank()) return params;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                try {
                    params.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                    // 非法编码交给 WAF/签名校验拒绝
                }
            }
        }
        return params;
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

    private WebSocket connectUpstream(GatewayRoute route, WebSocketSession session) throws Exception {
        Exception last = null;
        for (String base : weightedWebsocketTargets(route)) {
            try {
                WebSocket upstream = connectTo(route, session, base);
                session.getAttributes().put("upstreamBase", base);
                return upstream;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last == null ? new IllegalStateException("no websocket upstream") : last;
    }

    private WebSocket connectTo(GatewayRoute route, WebSocketSession session, String base) throws Exception {
        String target = websocketUrl(route, session, base);
        return httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(target), new UpstreamListener(session))
                .get(15, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** 已建立的下游连接在上游掉线时尝试切换到下一个上游。 */
    private void reconnect(WebSocketSession session, WebSocket failed) {
        synchronized (session) {
            if (!session.isOpen() || upstreams.get(session.getId()) != failed) return;
            GatewayRoute route = routes.get(session.getId());
            if (route == null) {
                closeQuietly(session, CloseStatus.SERVER_ERROR);
                return;
            }
            List<String> targets = weightedWebsocketTargets(route);
            String current = String.valueOf(session.getAttributes().getOrDefault("upstreamBase", ""));
            int start = Math.max(0, targets.indexOf(current) + 1);
            for (int i = 0; i < targets.size(); i++) {
                String base = targets.get((start + i) % targets.size());
                if (base.equals(current) && targets.size() > 1) continue;
                try {
                    WebSocket next = connectTo(route, session, base);
                    upstreams.put(session.getId(), next);
                    session.getAttributes().put("upstream", next);
                    session.getAttributes().put("upstreamBase", base);
                    upstreamPongAt.put(session.getId(), System.currentTimeMillis());
                    lastActivity.put(session.getId(), System.currentTimeMillis());
                    log.warn("WS 上游掉线切换成功 route={} {} -> {}", route.getId(), current, base);
                    return;
                } catch (Exception e) {
                    log.debug("WS 上游切换失败 {}: {}", base, e.getMessage());
                }
            }
            closeQuietly(session, CloseStatus.SERVER_ERROR.withReason("all upstreams unavailable"));
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) session.close(status);
        } catch (Exception ignored) {
            // session already closed
        }
    }

    /** 按路由权重生成 WS 首跳/切换顺序；未配置权重时保持主/备/额外顺序。 */
    private List<String> weightedWebsocketTargets(GatewayRoute route) {
        List<String> all = websocketTargets(route);
        Map<String, Integer> weights = websocketWeights(route);
        int total = all.stream().mapToInt(t -> Math.max(0, weights.getOrDefault(t, 0))).sum();
        if (total <= 0) return all;
        List<String> remaining = new ArrayList<>(all);
        List<String> ordered = new ArrayList<>();
        while (!remaining.isEmpty()) {
            int sum = remaining.stream().mapToInt(t -> Math.max(0, weights.getOrDefault(t, 0))).sum();
            if (sum <= 0) {
                ordered.addAll(remaining);
                break;
            }
            int pick = ThreadLocalRandom.current().nextInt(sum);
            int cursor = 0;
            for (int i = 0; i < remaining.size(); i++) {
                String target = remaining.get(i);
                cursor += Math.max(0, weights.getOrDefault(target, 0));
                if (pick < cursor) {
                    ordered.add(target);
                    remaining.remove(i);
                    break;
                }
            }
        }
        return ordered;
    }

    private Map<String, Integer> websocketWeights(GatewayRoute route) {
        Map<String, Integer> weights = new LinkedHashMap<>();
        for (String target : websocketTargets(route)) {
            weights.put(target, target.equals(normalize(route.getTargetUrl())) ? 100 : 0);
        }
        String raw = route.getUpstreamWeights();
        if (raw == null || raw.isBlank()) return weights;
        try {
            Map<?, ?> saved = objectMapper.readValue(raw, Map.class);
            for (Map.Entry<?, ?> entry : saved.entrySet()) {
                String url = normalize(String.valueOf(entry.getKey()));
                if (!url.isBlank() && weights.containsKey(url)) {
                    weights.put(url, Math.max(0, Math.min(1000,
                            Integer.parseInt(String.valueOf(entry.getValue())))));
                }
            }
        } catch (Exception e) {
            log.debug("WS route weights parse failed route={}: {}", route.getId(), e.getMessage());
        }
        return weights;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }

    private List<String> websocketTargets(GatewayRoute route) {
        List<String> targets = new ArrayList<>();
        if (route.getTargetUrl() != null && !route.getTargetUrl().isBlank()) {
            targets.add(route.getTargetUrl());
        }
        if (route.getFallbackUrl() != null && !route.getFallbackUrl().isBlank()) {
            targets.add(route.getFallbackUrl());
        }
        try {
            if (route.getUpstreamUrls() != null && !route.getUpstreamUrls().isBlank()) {
                for (String value : objectMapper.readValue(route.getUpstreamUrls(),
                        new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { })) {
                    if (value != null && !value.isBlank() && !targets.contains(value)) targets.add(value);
                }
            }
        } catch (Exception e) {
            log.warn("WS route upstream list parse failed route={}: {}", route.getId(), e.getMessage());
        }
        return targets;
    }

    private String websocketUrl(GatewayRoute route, WebSocketSession session, String rawBase) {
        String base = rawBase.replaceAll("/+$", "");
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
            reconnect(session, webSocket);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            reconnect(session, webSocket);
        }
    }
}
