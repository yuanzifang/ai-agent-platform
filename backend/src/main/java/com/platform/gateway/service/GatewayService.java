package com.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.common.security.NetworkPolicyService;
import com.platform.gateway.entity.GatewayRequestLog;
import com.platform.gateway.entity.GatewayRoute;
import com.platform.gateway.mapper.GatewayRequestLogMapper;
import com.platform.gateway.mapper.GatewayRouteMapper;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 统一 API 网关 MVP：路由、API Key、路径转发、限流、超时/重试、请求日志。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayService {

    private static final int MAX_BODY = 5 * 1024 * 1024;
    private static final long MAX_TARGET_BYTES = 10 * 1024 * 1024;

    private final GatewayRouteMapper routeMapper;
    private final GatewayRequestLogMapper logMapper;
    private final NetworkPolicyService networkPolicy;
    private final StringRedisTemplate redis;
    private final AuditService auditService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public List<Map<String, Object>> list() {
        return routeMapper.selectList(new LambdaQueryWrapper<GatewayRoute>()
                        .orderByDesc(GatewayRoute::getId))
                .stream().map(this::view).toList();
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> req, JwtPrincipal principal) {
        String name = text(req.get("name"), 128);
        String target = text(req.get("targetUrl"), 512);
        if (name == null) throw new BusinessException(400, "路由名称不能为空");
        networkPolicy.validateHttpUrl(target, "上游地址");
        GatewayRoute route = new GatewayRoute();
        route.setTenantId(principal.tenantId());
        route.setName(name);
        route.setRouteKey(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        String apiKey = newApiKey();
        route.setApiKeyHash(hash(apiKey));
        route.setApiKeyPrefix(apiKey.substring(0, 8));
        route.setTargetUrl(target.replaceAll("/+$", ""));
        String fallback = text(req.get("fallbackUrl"), 512);
        if (fallback != null) {
            networkPolicy.validateHttpUrl(fallback, "备用上游地址");
            fallback = fallback.replaceAll("/+$", "");
        }
        route.setFallbackUrl(fallback);
        route.setTimeoutMs(clamp(intValue(req.get("timeoutMs"), 15000), 1000, 120000));
        route.setRetries(clamp(intValue(req.get("retries"), 0), 0, 3));
        route.setRateLimitPerMin(clamp(intValue(req.get("rateLimitPerMin"), 60), 0, 100000));
        route.setEnabled(Boolean.FALSE.equals(req.get("enabled")) ? 0 : 1);
        route.setCreatedBy(principal.userId());
        route.setCreatedAt(LocalDateTime.now());
        route.setUpdatedAt(LocalDateTime.now());
        routeMapper.insert(route);
        auditService.record(principal.userId(), "gateway.route.create", "gateway_route",
                String.valueOf(route.getId()), "SUCCESS", Map.of("name", name));
        Map<String, Object> result = view(route);
        result.put("apiKey", apiKey);
        return result;
    }

    @Transactional
    public Map<String, Object> update(Long id, Map<String, Object> req, JwtPrincipal principal) {
        GatewayRoute route = require(id);
        if (req.containsKey("name")) {
            String name = text(req.get("name"), 128);
            if (name == null) throw new BusinessException(400, "路由名称不能为空");
            route.setName(name);
        }
        if (req.containsKey("targetUrl")) {
            String target = text(req.get("targetUrl"), 512);
            networkPolicy.validateHttpUrl(target, "上游地址");
            route.setTargetUrl(target.replaceAll("/+$", ""));
        }
        if (req.containsKey("fallbackUrl")) {
            String fallback = text(req.get("fallbackUrl"), 512);
            if (fallback != null) {
                networkPolicy.validateHttpUrl(fallback, "备用上游地址");
                fallback = fallback.replaceAll("/+$", "");
            }
            route.setFallbackUrl(fallback);
        }
        if (req.containsKey("timeoutMs")) route.setTimeoutMs(clamp(intValue(req.get("timeoutMs"), 15000), 1000, 120000));
        if (req.containsKey("retries")) route.setRetries(clamp(intValue(req.get("retries"), 0), 0, 3));
        if (req.containsKey("rateLimitPerMin")) route.setRateLimitPerMin(clamp(intValue(req.get("rateLimitPerMin"), 60), 0, 100000));
        if (req.containsKey("enabled")) route.setEnabled(Boolean.FALSE.equals(req.get("enabled")) ? 0 : 1);
        route.setUpdatedAt(LocalDateTime.now());
        routeMapper.updateById(route);
        auditService.record(principal.userId(), "gateway.route.update", "gateway_route",
                String.valueOf(id), "SUCCESS", Map.of("name", route.getName()));
        return view(route);
    }

    @Transactional
    public Map<String, Object> rotateKey(Long id, JwtPrincipal principal) {
        GatewayRoute route = require(id);
        String apiKey = newApiKey();
        route.setApiKeyHash(hash(apiKey));
        route.setApiKeyPrefix(apiKey.substring(0, 8));
        route.setUpdatedAt(LocalDateTime.now());
        routeMapper.updateById(route);
        auditService.record(principal.userId(), "gateway.route.rotate_key", "gateway_route",
                String.valueOf(id), "SUCCESS", Map.of("name", route.getName()));
        Map<String, Object> result = view(route);
        result.put("apiKey", apiKey);
        return result;
    }

    @Transactional
    public void delete(Long id, JwtPrincipal principal) {
        GatewayRoute route = require(id);
        routeMapper.deleteById(id);
        logMapper.delete(new LambdaQueryWrapper<GatewayRequestLog>()
                .eq(GatewayRequestLog::getRouteId, id));
        auditService.record(principal.userId(), "gateway.route.delete", "gateway_route",
                String.valueOf(id), "SUCCESS", Map.of("name", route.getName()));
    }

    public List<GatewayRequestLog> logs(Long id) {
        require(id);
        return logMapper.selectList(new LambdaQueryWrapper<GatewayRequestLog>()
                .eq(GatewayRequestLog::getRouteId, id)
                .orderByDesc(GatewayRequestLog::getId)
                .last("LIMIT 50"));
    }

    /** 公开代理入口：X-Gateway-Key 鉴权，路径后缀转发到上游。 */
    public ResponseEntity<byte[]> proxy(String routeKey, HttpServletRequest request) {
        long started = System.currentTimeMillis();
        GatewayRoute route = routeMapper.findByRouteKeyIgnore(routeKey);
        if (route == null) return error(HttpStatus.NOT_FOUND, "网关路由不存在");
        if (route.getEnabled() == null || route.getEnabled() != 1) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "网关路由已停用");
        }
        String provided = request.getHeader("X-Gateway-Key");
        if (provided == null || !MessageDigest.isEqual(
                hash(provided).getBytes(StandardCharsets.UTF_8),
                route.getApiKeyHash().getBytes(StandardCharsets.UTF_8))) {
            log(route, request, 401, null, started, "invalid api key");
            return error(HttpStatus.UNAUTHORIZED, "网关 API Key 无效");
        }
        if (!allow(route)) {
            log(route, request, 429, null, started, "rate limit");
            return error(HttpStatus.TOO_MANY_REQUESTS, "网关路由限流");
        }
        String suffix = suffix(routeKey, request.getRequestURI());
        String query = request.getQueryString();
        try {
            byte[] body = "GET".equalsIgnoreCase(request.getMethod()) || "HEAD".equalsIgnoreCase(request.getMethod())
                    ? new byte[0] : request.getInputStream().readAllBytes();
            if (body.length > MAX_BODY) {
                log(route, request, 413, null, started, "body too large");
                return error(HttpStatus.PAYLOAD_TOO_LARGE, "请求体不能超过 5MB");
            }
            List<String> targets = new ArrayList<>();
            targets.add(route.getTargetUrl());
            if (route.getFallbackUrl() != null && !route.getFallbackUrl().isBlank()) {
                targets.add(route.getFallbackUrl());
            }
            Exception last = null;
            for (int ti = 0; ti < targets.size(); ti++) {
                String target = targets.get(ti) + suffix
                        + (query == null || query.isBlank() ? "" : "?" + query);
                try {
                    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target))
                            .timeout(Duration.ofMillis(route.getTimeoutMs() == null ? 15000 : route.getTimeoutMs()));
                    HttpRequest.BodyPublisher publisher = body.length == 0
                            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body);
                    builder.method(request.getMethod().toUpperCase(Locale.ROOT), publisher);
                    copyHeader(request, builder, "Content-Type");
                    copyHeader(request, builder, "Accept");
                    copyHeader(request, builder, "User-Agent");
                    HttpRequest httpRequest = builder.build();

                    int attempts = 1 + (isIdempotent(request.getMethod())
                            ? Math.max(0, route.getRetries() == null ? 0 : route.getRetries()) : 0);
                    HttpResponse<byte[]> response = null;
                    Exception attemptError = null;
                    for (int i = 0; i < attempts; i++) {
                        try {
                            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
                            if (response.statusCode() < 500 || i == attempts - 1) break;
                        } catch (Exception e) {
                            attemptError = e;
                            if (i == attempts - 1) throw e;
                        }
                    }
                    if (response == null) {
                        throw attemptError == null ? new IllegalStateException("no upstream response") : attemptError;
                    }
                    if (response.statusCode() >= 500 && ti < targets.size() - 1) {
                        last = new IllegalStateException("upstream " + response.statusCode());
                        continue;
                    }
                    if (response.body().length > MAX_TARGET_BYTES) {
                        log(route, request, 502, response.statusCode(), started, "upstream response too large");
                        return error(HttpStatus.BAD_GATEWAY, "上游响应超过 10MB");
                    }
                    log(route, request, response.statusCode(), response.statusCode(), started,
                            ti > 0 ? "fallback upstream" : null);
                    HttpHeaders headers = new HttpHeaders();
                    response.headers().firstValue("Content-Type").ifPresent(v -> headers.set(HttpHeaders.CONTENT_TYPE, v));
                    response.headers().firstValue("Content-Disposition").ifPresent(v -> headers.set(HttpHeaders.CONTENT_DISPOSITION, v));
                    return new ResponseEntity<>(response.body(), headers, HttpStatus.valueOf(response.statusCode()));
                } catch (Exception e) {
                    last = e;
                    if (ti == targets.size() - 1) throw e;
                }
            }
            throw last == null ? new IllegalStateException("no upstream response") : last;
        } catch (Exception e) {
            log(route, request, 502, null, started, root(e));
            return error(HttpStatus.BAD_GATEWAY, "上游调用失败：" + root(e));
        }
    }

    private void log(GatewayRoute route, HttpServletRequest request, int status, Integer upstream,
                     long started, String error) {
        try {
            TenantContext.runWith(route.getTenantId(), () -> {
                GatewayRequestLog row = new GatewayRequestLog();
                row.setTenantId(route.getTenantId());
                row.setRouteId(route.getId());
                row.setMethod(request.getMethod());
                row.setPath(truncate(request.getRequestURI(), 512));
                row.setStatus(status);
                row.setUpstreamStatus(upstream);
                row.setLatencyMs(System.currentTimeMillis() - started);
                row.setError(error == null ? null : truncate(error, 512));
                row.setCreatedAt(LocalDateTime.now());
                logMapper.insert(row);
                return null;
            });
        } catch (Exception e) {
            log.debug("gateway log insert failed: {}", e.getMessage());
        }
    }

    private boolean allow(GatewayRoute route) {
        int limit = route.getRateLimitPerMin() == null ? 0 : route.getRateLimitPerMin();
        if (limit <= 0) return true;
        try {
            String bucket = "ratelimit:gw:" + route.getId() + ":" + (System.currentTimeMillis() / 60_000);
            Long count = redis.opsForValue().increment(bucket);
            if (count != null && count == 1L) redis.expire(bucket, Duration.ofSeconds(70));
            return count == null || count <= limit;
        } catch (Exception e) {
            // Redis 故障时放行并记录（网关可用性优先；限流计数缺失可在日志侧发现）
            log.warn("网关限流检查失败（已放行）：route={} {}", route.getId(), e.getMessage());
            return true;
        }
    }

    private String suffix(String routeKey, String uri) {
        String marker = "/api/v1/gateway/call/" + routeKey;
        String suffix = uri.startsWith(marker) ? uri.substring(marker.length()) : "/";
        if (suffix.isBlank()) suffix = "/";
        if (!suffix.startsWith("/")) suffix = "/" + suffix;
        return suffix;
    }

    private void copyHeader(HttpServletRequest request, HttpRequest.Builder builder, String name) {
        String value = request.getHeader(name);
        if (value != null && !value.isBlank()) builder.header(name, value);
    }

    private boolean isIdempotent(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
    }

    private GatewayRoute require(Long id) {
        GatewayRoute route = routeMapper.selectById(id);
        if (route == null) throw new BusinessException(404, "网关路由不存在");
        return route;
    }

    private Map<String, Object> view(GatewayRoute route) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", route.getId());
        m.put("name", route.getName());
        m.put("routeKey", route.getRouteKey());
        m.put("apiKeyPrefix", route.getApiKeyPrefix());
        m.put("targetUrl", route.getTargetUrl());
        m.put("fallbackUrl", route.getFallbackUrl());
        m.put("timeoutMs", route.getTimeoutMs());
        m.put("retries", route.getRetries());
        m.put("rateLimitPerMin", route.getRateLimitPerMin());
        m.put("enabled", route.getEnabled() != null && route.getEnabled() == 1);
        m.put("createdAt", route.getCreatedAt());
        m.put("updatedAt", route.getUpdatedAt());
        return m;
    }

    private ResponseEntity<byte[]> error(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(("{\"code\":" + status.value() + ",\"message\":\"" + message.replace("\"", "'") + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private String newApiKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return "gw_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String text(Object raw, int max) {
        if (raw == null) return null;
        String value = String.valueOf(raw).trim();
        if (value.isEmpty()) return null;
        return truncate(value, max);
    }

    private int intValue(Object raw, int fallback) {
        try {
            return raw == null ? fallback : Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private String root(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return truncate(msg, 200);
    }
}
