package com.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.common.validation.ToolInputValidator;
import com.platform.common.security.CryptoService;
import com.platform.common.security.NetworkPolicyService;
import com.platform.gateway.entity.GatewayApiKey;
import com.platform.gateway.entity.GatewayRequestLog;
import com.platform.gateway.entity.GatewayRoute;
import com.platform.gateway.mapper.GatewayRequestLogMapper;
import com.platform.gateway.mapper.GatewayRouteMapper;
import com.platform.security.JwtPrincipal;
import com.platform.system.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** 统一 API 网关 MVP：路由、API Key、路径转发、限流、超时/重试、请求日志。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayService {

    private static final int MAX_BODY = 5 * 1024 * 1024;
    private static final long MAX_TARGET_BYTES = 10 * 1024 * 1024;
    private static final int UPSTREAM_FAIL_THRESHOLD = 3;
    private static final Duration UPSTREAM_FAIL_WINDOW = Duration.ofMinutes(2);
    private static final Duration UPSTREAM_COOLDOWN = Duration.ofSeconds(60);

    private final GatewayRouteMapper routeMapper;
    private final GatewayRequestLogMapper logMapper;
    private final NetworkPolicyService networkPolicy;
    private final StringRedisTemplate redis;
    private final AuditService auditService;
    private final GatewayApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;
    private final GatewayHealthService healthService;
    private final CryptoService cryptoService;
    private final ToolInputValidator inputValidator;
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
        List<String> upstreams = urls(req.get("upstreamUrls"));
        if (upstreams.size() > 5) throw new BusinessException(400, "额外上游最多 5 个");
        for (String upstream : upstreams) {
            networkPolicy.validateHttpUrl(upstream, "额外上游地址");
        }
        route.setUpstreamUrls(upstreams.isEmpty() ? null : toJson(upstreams));
        Map<String, Integer> weights = new LinkedHashMap<>();
        applyDefaultWeights(weights, route.getTargetUrl(), route.getFallbackUrl(), upstreams);
        applyRequestWeights(weights, req, route.getTargetUrl(), route.getFallbackUrl());
        route.setUpstreamWeights(toJson(weights));
        route.setTimeoutMs(clamp(intValue(req.get("timeoutMs"), 15000), 1000, 120000));
        route.setRetries(clamp(intValue(req.get("retries"), 0), 0, 3));
        route.setRateLimitPerMin(clamp(intValue(req.get("rateLimitPerMin"), 60), 0, 100000));
        route.setHealthPath(healthPath(req.get("healthPath")));
        route.setHealthCheckEnabled(Boolean.FALSE.equals(req.get("healthCheckEnabled")) ? 0 : 1);
        route.setWsMaxConnections(clamp(intValue(req.get("wsMaxConnections"), 100), 0, 10000));
        String signingSecret = null;
        if (Boolean.TRUE.equals(req.get("signingEnabled"))) {
            signingSecret = newSigningSecret();
            route.setSigningSecret(cryptoService.encrypt(signingSecret));
        }
        route.setRequestSchema(requestSchema(req.get("requestSchema")));
        route.setEnabled(Boolean.FALSE.equals(req.get("enabled")) ? 0 : 1);
        route.setCreatedBy(principal.userId());
        route.setCreatedAt(LocalDateTime.now());
        route.setUpdatedAt(LocalDateTime.now());
        routeMapper.insert(route);
        auditService.record(principal.userId(), "gateway.route.create", "gateway_route",
                String.valueOf(route.getId()), "SUCCESS", Map.of("name", name));
        Map<String, Object> result = view(route);
        result.put("apiKey", apiKey);
        if (signingSecret != null) result.put("signingSecret", signingSecret);
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
        if (req.containsKey("upstreamUrls")) {
            List<String> upstreams = urls(req.get("upstreamUrls"));
            if (upstreams.size() > 5) throw new BusinessException(400, "额外上游最多 5 个");
            for (String upstream : upstreams) {
                networkPolicy.validateHttpUrl(upstream, "额外上游地址");
            }
            route.setUpstreamUrls(upstreams.isEmpty() ? null : toJson(upstreams));
        }
        if (req.containsKey("targetUrl") || req.containsKey("fallbackUrl") || req.containsKey("upstreamUrls")
                || req.containsKey("targetWeight") || req.containsKey("fallbackWeight") || req.containsKey("upstreamWeights")) {
            Map<String, Integer> weights = weights(route);
            applyDefaultWeights(weights, route.getTargetUrl(), route.getFallbackUrl(), urls(route.getUpstreamUrls()));
            applyRequestWeights(weights, req, route.getTargetUrl(), route.getFallbackUrl());
            route.setUpstreamWeights(toJson(weights));
        }
        if (req.containsKey("timeoutMs")) route.setTimeoutMs(clamp(intValue(req.get("timeoutMs"), 15000), 1000, 120000));
        if (req.containsKey("retries")) route.setRetries(clamp(intValue(req.get("retries"), 0), 0, 3));
        if (req.containsKey("rateLimitPerMin")) route.setRateLimitPerMin(clamp(intValue(req.get("rateLimitPerMin"), 60), 0, 100000));
        if (req.containsKey("healthPath")) route.setHealthPath(healthPath(req.get("healthPath")));
        if (req.containsKey("healthCheckEnabled")) {
            route.setHealthCheckEnabled(Boolean.FALSE.equals(req.get("healthCheckEnabled")) ? 0 : 1);
        }
        if (req.containsKey("wsMaxConnections")) {
            route.setWsMaxConnections(clamp(intValue(req.get("wsMaxConnections"), 100), 0, 10000));
        }
        String newSigningSecret = null;
        if (req.containsKey("signingEnabled")) {
            boolean signingEnabled = Boolean.TRUE.equals(req.get("signingEnabled"));
            if (!signingEnabled) {
                route.setSigningSecret(null);
            } else if (route.getSigningSecret() == null || route.getSigningSecret().isBlank()) {
                newSigningSecret = newSigningSecret();
                route.setSigningSecret(cryptoService.encrypt(newSigningSecret));
            }
        }
        if (req.containsKey("requestSchema")) route.setRequestSchema(requestSchema(req.get("requestSchema")));
        if (req.containsKey("enabled")) route.setEnabled(Boolean.FALSE.equals(req.get("enabled")) ? 0 : 1);
        route.setUpdatedAt(LocalDateTime.now());
        routeMapper.updateById(route);
        auditService.record(principal.userId(), "gateway.route.update", "gateway_route",
                String.valueOf(id), "SUCCESS", Map.of("name", route.getName()));
        Map<String, Object> result = view(route);
        if (newSigningSecret != null) result.put("signingSecret", newSigningSecret);
        return result;
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
        for (String upstream : targets(route)) clearCircuit(id, upstream);
        healthService.clearRoute(id);
        clearGatewayRateLimit(id);
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

    public List<Map<String, Object>> apiKeys(Long routeId) {
        return apiKeyService.listView(routeId);
    }

    public Map<String, Object> apiKeyView(com.platform.gateway.entity.GatewayApiKey key) {
        return apiKeyService.apiKeyView(key);
    }

    public Map<String, Object> createKey(Long routeId, Map<String, Object> req,
                                         com.platform.security.JwtPrincipal principal,
                                         java.time.LocalDateTime expiresAt) {
        var created = apiKeyService.create(routeId, principal.tenantId(),
                String.valueOf(req.getOrDefault("name", "")),
                req.get("rateLimitPerMin") instanceof Number n ? n.intValue() : null,
                req.get("dailyQuota") instanceof Number n ? n.intValue() : null,
                req.get("allowedMethods") == null ? null : String.valueOf(req.get("allowedMethods")),
                req.get("allowedPaths") == null ? null : String.valueOf(req.get("allowedPaths")),
                expiresAt, principal.userId());
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", created.key().getId());
        out.put("name", created.key().getName());
        out.put("keyPrefix", created.key().getKeyPrefix());
        out.put("apiKey", created.apiKey());   // 仅创建时返回一次
        return out;
    }

    public void updateKey(Long keyId, Map<String, Object> req, com.platform.security.JwtPrincipal principal) {
        java.time.LocalDateTime expiresAt = null;
        if (req.get("expiresAt") != null && !String.valueOf(req.get("expiresAt")).isBlank()) {
            expiresAt = java.time.LocalDateTime.parse(String.valueOf(req.get("expiresAt")));
        }
        apiKeyService.update(keyId,
                req.get("name") == null ? null : String.valueOf(req.get("name")),
                req.get("enabled") instanceof Boolean b ? b : null,
                req.get("rateLimitPerMin") instanceof Number n ? n.intValue() : null,
                req.get("dailyQuota") instanceof Number n ? n.intValue() : null,
                req.get("allowedMethods") == null ? null : String.valueOf(req.get("allowedMethods")),
                req.get("allowedPaths") == null ? null : String.valueOf(req.get("allowedPaths")),
                expiresAt, principal.userId());
    }

    public void deleteApiKey(Long keyId, com.platform.security.JwtPrincipal principal) {
        apiKeyService.delete(keyId, principal.userId());
    }

    /** 查询路由上游健康状态（定时任务最近一次探测结果）。 */
    public List<Map<String, Object>> health(Long id) {
        return healthService.view(require(id));
    }

    /** 立即触发一次健康检查并返回结果。 */
    public List<Map<String, Object>> checkHealth(Long id) {
        return healthService.checkRoute(require(id));
    }

    /** 公开代理入口：X-Gateway-Key 鉴权，路径后缀转发到上游。 */
    public StreamingResponseBody proxy(String routeKey, HttpServletRequest request,
                                       HttpServletResponse servletResponse) {
        long started = System.currentTimeMillis();
        GatewayRoute route = routeMapper.findByRouteKeyIgnore(routeKey);
        if (route == null) return error(servletResponse, HttpStatus.NOT_FOUND, "网关路由不存在");
        if (route.getEnabled() == null || route.getEnabled() != 1) {
            return error(servletResponse, HttpStatus.SERVICE_UNAVAILABLE, "网关路由已停用");
        }
        String waf = wafViolation(request);
        if (waf != null) {
            log(route, request, 400, null, started, "waf: " + waf);
            return error(servletResponse, HttpStatus.BAD_REQUEST, "请求被 WAF 拒绝：" + waf);
        }
        String provided = request.getHeader("X-Gateway-Key");
        if (provided == null || provided.isBlank()) {
            log(route, request, 401, null, started, "missing api key");
            return error(servletResponse, HttpStatus.UNAUTHORIZED, "缺少 X-Gateway-Key");
        }
        // 1) 调用方 Key（gateway_api_key，独立禁用/过期/按 Key 限流）
        GatewayApiKey callerKey = apiKeyService.matchEnabledKey(provided);
        if (callerKey != null && !callerKey.getRouteId().equals(route.getId())) {
            log(route, request, 403, null, started, "key belongs to another route");
            return error(servletResponse, HttpStatus.FORBIDDEN, "API Key 与路由不匹配");
        }
        if (callerKey != null && !apiKeyService.allowByKey(callerKey)) {
            log(route, request, 429, null, started, "key rate limit");
            return error(servletResponse, HttpStatus.TOO_MANY_REQUESTS, "调用方限流（按 Key）");
        }
        if (callerKey != null && !apiKeyService.allowDaily(callerKey)) {
            log(route, request, 429, null, started, "key daily quota");
            return error(servletResponse, HttpStatus.TOO_MANY_REQUESTS, "调用方每日配额已用尽");
        }
        if (callerKey != null) apiKeyService.markUsed(callerKey);
        final GatewayApiKey usedCallerKey = callerKey;
        // 2) 回落路由级固定 Key（历史兼容），未中调用方 Key 时校验
        if (callerKey == null && !MessageDigest.isEqual(
                hash(provided).getBytes(StandardCharsets.UTF_8),
                route.getApiKeyHash().getBytes(StandardCharsets.UTF_8))) {
            log(route, request, 401, null, started, "invalid api key");
            return error(servletResponse, HttpStatus.UNAUTHORIZED, "网关 API Key 无效");
        }
        // 3) 路由级限流（调用方 Key 未配独立限流时兜底）
        if ((callerKey == null || callerKey.getRateLimitPerMin() == null
                || callerKey.getRateLimitPerMin() <= 0) && !allow(route)) {
            log(route, request, 429, null, started, callerKey != null ? "rate limit (key " + callerKey.getId() + ")" : "rate limit");
            return error(servletResponse, HttpStatus.TOO_MANY_REQUESTS, "网关路由限流");
        }
        String suffix = suffix(routeKey, request.getRequestURI());
        if (!apiKeyService.scopeAllows(callerKey, request.getMethod(), suffix)) {
            log(route, request, 403, null, started, "api key scope denied");
            return error(servletResponse, HttpStatus.FORBIDDEN, "API Key scope 不允许该请求");
        }
        String query = request.getQueryString();
        try {
            byte[] body = "GET".equalsIgnoreCase(request.getMethod()) || "HEAD".equalsIgnoreCase(request.getMethod())
                    ? new byte[0] : request.getInputStream().readAllBytes();
            if (body.length > MAX_BODY) {
                log(route, request, 413, null, started, "body too large");
                return error(servletResponse, HttpStatus.PAYLOAD_TOO_LARGE, "请求体不能超过 5MB");
            }
            String signatureError = verifySignature(route, request, body);
            if (signatureError != null) {
                int status = "replay".equals(signatureError) ? 409 : 401;
                log(route, request, status, null, started, "signature: " + signatureError);
                return error(servletResponse, status == 409 ? HttpStatus.CONFLICT : HttpStatus.UNAUTHORIZED,
                        "请求签名校验失败：" + signatureError);
            }
            if (route.getRequestSchema() != null && !route.getRequestSchema().isBlank()) {
                ToolInputValidator.Result validation = inputValidator.validate(
                        route.getRequestSchema(), new String(body, StandardCharsets.UTF_8));
                if (!validation.valid()) {
                    String detail = validation.violations().isEmpty() ? "invalid body"
                            : validation.violations().get(0).path() + " " + validation.violations().get(0).rule();
                    log(route, request, 400, null, started, "schema: " + detail);
                    return error(servletResponse, HttpStatus.BAD_REQUEST, "请求体不符合 Schema：" + detail);
                }
            }
            List<String> targets = weightedTargets(route);
            java.util.Set<String> unhealthy = healthService.unhealthyTargets(route.getId(), targets);
            boolean healthAware = unhealthy.size() < targets.size();
            if (wantsEventStream(request)) {
                return proxyStream(route, request, body, query, targets, suffix, started, servletResponse);
            }
            Exception last = null;
            boolean primaryCircuitOpen = false;
            for (int ti = 0; ti < targets.size(); ti++) {
                String upstream = targets.get(ti);
                if (healthAware && unhealthy.contains(upstream)) {
                    last = new IllegalStateException("upstream unhealthy");
                    continue;
                }
                if (circuitOpen(route.getId(), upstream)) {
                    if (ti < targets.size() - 1) {
                        primaryCircuitOpen = true;
                        last = new IllegalStateException("primary upstream circuit open");
                        continue;
                    }
                    log(route, request, 503, null, started, "upstream circuit open");
                    return error(servletResponse, HttpStatus.SERVICE_UNAVAILABLE, "上游熔断中，请稍后重试");
                }
                String target = upstream + suffix
                        + (query == null || query.isBlank() ? "" : "?" + query);
                // 存储的旧配置可能绕过创建时的校验；发送前按当前网络策略再校验一次
                networkPolicy.validateHttpUrl(target, "上游地址");
                try {
                    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target))
                            .timeout(Duration.ofMillis(route.getTimeoutMs() == null ? 15000 : route.getTimeoutMs()));
                    HttpRequest.BodyPublisher publisher = body.length == 0
                            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body);
                    builder.method(request.getMethod().toUpperCase(Locale.ROOT), publisher);
                    copyHeader(request, builder, "Content-Type");
                    copyHeader(request, builder, "Accept");
                    copyHeader(request, builder, "User-Agent");
                    copyHeader(request, builder, "Authorization");
                    copyHeader(request, builder, "X-Gray-Tag");   // 灰度标记全链路透传
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
                    if (response.statusCode() >= 500) recordUpstreamFailure(route.getId(), upstream);
                    else recordUpstreamSuccess(route.getId(), upstream);
                    if (response.statusCode() >= 500 && ti < targets.size() - 1) {
                        last = new IllegalStateException("upstream " + response.statusCode());
                        continue;
                    }
                    if (response.body().length > MAX_TARGET_BYTES) {
                        log(route, request, 502, response.statusCode(), started, "upstream response too large");
                        return error(servletResponse, HttpStatus.BAD_GATEWAY, "上游响应超过 10MB");
                    }
                    log(route, request, response.statusCode(), response.statusCode(), started,
                            ti > 0 ? (primaryCircuitOpen ? "fallback upstream; primary circuit open"
                                    : "fallback upstream") : null);
                    HttpHeaders headers = new HttpHeaders();
                    response.headers().firstValue("Content-Type").ifPresent(v -> headers.set(HttpHeaders.CONTENT_TYPE, v));
                    response.headers().firstValue("Content-Disposition").ifPresent(v -> headers.set(HttpHeaders.CONTENT_DISPOSITION, v));
                    applyHeaders(servletResponse, headers, response.statusCode());
                    byte[] resultBody = response.body();
                    servletResponse.setContentLength(resultBody.length);
                    return out -> out.write(resultBody);
                } catch (Exception e) {
                    recordUpstreamFailure(route.getId(), upstream);
                    last = e;
                    if (ti == targets.size() - 1) throw e;
                }
            }
            throw last == null ? new IllegalStateException("no upstream response") : last;
        } catch (Exception e) {
            log(route, request, 502, null, started, root(e));
            return error(servletResponse, HttpStatus.BAD_GATEWAY, "上游调用失败：" + root(e));
        }
    }

    /**
     * SSE/流式响应透传：拿到上游响应头后逐块 flush，不做整包缓冲。
     * 日志中的 latency 是到响应头的时间，不代表整个流结束时间。
     */
    private StreamingResponseBody proxyStream(GatewayRoute route, HttpServletRequest request, byte[] body,
                                              String query, List<String> targets, String suffix,
                                              long started, HttpServletResponse servletResponse) {
        Exception last = null;
        boolean primaryCircuitOpen = false;
        java.util.Set<String> unhealthy = healthService.unhealthyTargets(route.getId(), targets);
        boolean healthAware = unhealthy.size() < targets.size();
        for (int ti = 0; ti < targets.size(); ti++) {
            String upstreamBase = targets.get(ti);
            if (healthAware && unhealthy.contains(upstreamBase)) {
                last = new IllegalStateException("upstream unhealthy");
                continue;
            }
            if (circuitOpen(route.getId(), upstreamBase)) {
                if (ti < targets.size() - 1) {
                    primaryCircuitOpen = true;
                    last = new IllegalStateException("primary upstream circuit open");
                    continue;
                }
                log(route, request, 503, null, started, "upstream circuit open");
                return error(servletResponse, HttpStatus.SERVICE_UNAVAILABLE, "上游熔断中，请稍后重试");
            }
            String target = upstreamBase + suffix
                    + (query == null || query.isBlank() ? "" : "?" + query);
            networkPolicy.validateHttpUrl(target, "上游地址");
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target))
                        .timeout(Duration.ofSeconds(300));
                HttpRequest.BodyPublisher publisher = body.length == 0
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body);
                builder.method(request.getMethod().toUpperCase(Locale.ROOT), publisher);
                copyHeader(request, builder, "Content-Type");
                copyHeader(request, builder, "Accept");
                copyHeader(request, builder, "User-Agent");
                copyHeader(request, builder, "Authorization");
                copyHeader(request, builder, "X-Gray-Tag");   // 灰度标记全链路透传
                HttpRequest httpRequest = builder.build();

                HttpResponse<InputStream> response = httpClient.send(
                        httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() >= 500) recordUpstreamFailure(route.getId(), upstreamBase);
                else recordUpstreamSuccess(route.getId(), upstreamBase);
                if (response.statusCode() >= 500 && ti < targets.size() - 1) {
                    try (InputStream ignored = response.body()) {
                        last = new IllegalStateException("upstream " + response.statusCode());
                    }
                    continue;
                }
                HttpHeaders headers = new HttpHeaders();
                String upstreamType = response.headers().firstValue("Content-Type").orElse(null);
                headers.set(HttpHeaders.CONTENT_TYPE,
                        upstreamType == null || upstreamType.isBlank() ? "text/event-stream" : upstreamType);
                response.headers().firstValue("Cache-Control")
                        .ifPresent(v -> headers.set(HttpHeaders.CACHE_CONTROL, v));
                headers.set("X-Accel-Buffering", "no");

                if (response.statusCode() >= 500) {
                    byte[] errorBody;
                    try (InputStream in = response.body()) {
                        errorBody = in.readAllBytes();
                    }
                    log(route, request, response.statusCode(), response.statusCode(), started,
                            ti > 0 ? (primaryCircuitOpen ? "fallback upstream; primary circuit open"
                                    : "fallback upstream") : null);
                    applyHeaders(servletResponse, headers, response.statusCode());
                    servletResponse.setContentLength(errorBody.length);
                    return out -> out.write(errorBody);
                }

                log(route, request, response.statusCode(), response.statusCode(), started,
                        ti > 0 ? (primaryCircuitOpen ? "fallback upstream; primary circuit open"
                                : "fallback upstream") : null);
                InputStream upstreamBody = response.body();
                StreamingResponseBody stream = out -> {
                    try (InputStream in = upstreamBody) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            out.flush();
                        }
                    }
                };
                applyHeaders(servletResponse, headers, response.statusCode());
                return stream;
            } catch (Exception e) {
                recordUpstreamFailure(route.getId(), upstreamBase);
                last = e;
                if (ti == targets.size() - 1) {
                    log(route, request, 502, null, started, root(e));
                    return error(servletResponse, HttpStatus.BAD_GATEWAY, "上游流式调用失败：" + root(e));
                }
            }
        }
        return error(servletResponse, HttpStatus.BAD_GATEWAY, "上游流式调用失败："
                + (last == null ? "无可用上游" : root(last)));
    }

    private boolean wantsEventStream(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.toLowerCase(Locale.ROOT).contains("text/event-stream");
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

    /** 轻量熔断：连续 3 次 5xx/IO 失败后短路 60 秒；Redis 故障时 fail-open。 */
    private boolean circuitOpen(Long routeId, String upstream) {
        if (routeId == null || upstream == null || upstream.isBlank()) return false;
        try {
            return Boolean.TRUE.equals(redis.hasKey(circuitKey("open", routeId, upstream)));
        } catch (Exception e) {
            return false;
        }
    }

    private void recordUpstreamFailure(Long routeId, String upstream) {
        if (routeId == null || upstream == null || upstream.isBlank()) return;
        try {
            String failKey = circuitKey("fail", routeId, upstream);
            Long count = redis.opsForValue().increment(failKey);
            if (count != null && count == 1L) redis.expire(failKey, UPSTREAM_FAIL_WINDOW);
            if (count != null && count >= UPSTREAM_FAIL_THRESHOLD) {
                redis.opsForValue().set(circuitKey("open", routeId, upstream), "1", UPSTREAM_COOLDOWN);
                log.warn("网关上游熔断打开 route={} upstream={}", routeId, upstream);
            }
        } catch (Exception e) {
            log.debug("网关熔断计数失败（忽略）：{}", e.getMessage());
        }
    }

    private void recordUpstreamSuccess(Long routeId, String upstream) {
        if (routeId == null || upstream == null || upstream.isBlank()) return;
        try {
            redis.delete(circuitKey("fail", routeId, upstream));
            redis.delete(circuitKey("open", routeId, upstream));
        } catch (Exception e) {
            log.debug("网关熔断状态清理失败（忽略）：{}", e.getMessage());
        }
    }

    private void clearCircuit(Long routeId, String upstream) {
        recordUpstreamSuccess(routeId, upstream);
    }

    private void clearGatewayRateLimit(Long routeId) {
        try {
            var keys = redis.keys("ratelimit:gw:" + routeId + ":*");
            if (keys != null && !keys.isEmpty()) redis.delete(keys);
        } catch (Exception e) {
            log.debug("网关限流键清理失败（忽略）：{}", e.getMessage());
        }
    }

    private String circuitKey(String kind, Long routeId, String upstream) {
        return "gateway:cb:" + kind + ":" + routeId + ":" + Integer.toHexString(upstream.hashCode());
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

    /** 高置信度 WAF；具体规则见 GatewayWaf。 */
    private String wafViolation(HttpServletRequest request) {
        return GatewayWaf.check(request.getMethod(), request.getRequestURI(), request.getQueryString());
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

    /** 按权重为每次请求生成尝试顺序；权重全为 0 时保持原故障切换顺序。 */
    List<String> weightedTargets(GatewayRoute route) {
        List<String> all = targets(route);
        Map<String, Integer> weights = weights(route);
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

    private Map<String, Integer> weights(GatewayRoute route) {
        Map<String, Integer> weights = new LinkedHashMap<>();
        applyDefaultWeights(weights, route.getTargetUrl(), route.getFallbackUrl(), urls(route.getUpstreamUrls()));
        String raw = route.getUpstreamWeights();
        if (raw == null || raw.isBlank()) return weights;
        try {
            Map<?, ?> saved = objectMapper.readValue(raw, Map.class);
            for (Map.Entry<?, ?> entry : saved.entrySet()) {
                String url = normalizeUrl(String.valueOf(entry.getKey()));
                if (url.isBlank()) continue;
                weights.put(url, clamp(intValue(entry.getValue(), 0), 0, 1000));
            }
        } catch (Exception e) {
            log.debug("网关权重解析失败 route={}：{}", route.getId(), e.getMessage());
        }
        return weights;
    }

    private void applyDefaultWeights(Map<String, Integer> weights, String target, String fallback,
                                     List<String> extras) {
        if (target != null && !target.isBlank()) weights.putIfAbsent(normalizeUrl(target), 100);
        if (fallback != null && !fallback.isBlank()) weights.putIfAbsent(normalizeUrl(fallback), 0);
        for (String extra : extras) {
            if (extra != null && !extra.isBlank()) weights.putIfAbsent(normalizeUrl(extra), 0);
        }
    }

    private void applyRequestWeights(Map<String, Integer> weights, Map<String, Object> req,
                                     String target, String fallback) {
        if (req.containsKey("targetWeight") && target != null) {
            weights.put(normalizeUrl(target), clamp(intValue(req.get("targetWeight"), 100), 0, 1000));
        }
        if (req.containsKey("fallbackWeight") && fallback != null) {
            weights.put(normalizeUrl(fallback), clamp(intValue(req.get("fallbackWeight"), 0), 0, 1000));
        }
        Object raw = req.get("upstreamWeights");
        if (raw == null) return;
        try {
            Map<?, ?> incoming = raw instanceof Map<?, ?> map ? map
                    : objectMapper.readValue(String.valueOf(raw), Map.class);
            for (Map.Entry<?, ?> entry : incoming.entrySet()) {
                String url = normalizeUrl(String.valueOf(entry.getKey()));
                if (!url.isBlank()) weights.put(url, clamp(intValue(entry.getValue(), 0), 0, 1000));
            }
        } catch (Exception e) {
            throw new BusinessException(400, "上游权重格式错误");
        }
    }

    private String normalizeUrl(String value) {
        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }

    private List<String> targets(GatewayRoute route) {
        List<String> targets = new ArrayList<>();
        if (route.getTargetUrl() != null && !route.getTargetUrl().isBlank()) targets.add(route.getTargetUrl());
        if (route.getFallbackUrl() != null && !route.getFallbackUrl().isBlank()) targets.add(route.getFallbackUrl());
        for (String upstream : urls(route.getUpstreamUrls())) {
            if (!targets.contains(upstream)) targets.add(upstream);
        }
        return targets;
    }

    private List<String> urls(Object raw) {
        if (raw == null) return List.of();
        try {
            if (raw instanceof List<?> list) {
                return list.stream().map(String::valueOf).map(String::trim)
                        .filter(v -> !v.isBlank()).map(v -> v.replaceAll("/+$", "")).toList();
            }
            if (raw instanceof String text) {
                String value = text.trim();
                if (value.startsWith("[")) {
                    return objectMapper.readValue(value, new TypeReference<List<String>>() { })
                            .stream().map(String::trim).filter(v -> !v.isBlank())
                            .map(v -> v.replaceAll("/+$", "")).toList();
                }
                return java.util.Arrays.stream(value.split(",")).map(String::trim)
                        .filter(v -> !v.isBlank()).map(v -> v.replaceAll("/+$", "")).toList();
            }
        } catch (Exception e) {
            throw new BusinessException(400, "上游地址列表格式错误");
        }
        return List.of();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(400, "上游地址列表格式错误");
        }
    }

    private Map<String, Object> view(GatewayRoute route) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", route.getId());
        m.put("name", route.getName());
        m.put("routeKey", route.getRouteKey());
        m.put("apiKeyPrefix", route.getApiKeyPrefix());
        m.put("targetUrl", route.getTargetUrl());
        m.put("fallbackUrl", route.getFallbackUrl());
        m.put("upstreamUrls", urls(route.getUpstreamUrls()));
        Map<String, Integer> weights = weights(route);
        m.put("upstreamWeights", weights);
        m.put("targetWeight", weights.getOrDefault(route.getTargetUrl(), 100));
        m.put("fallbackWeight", route.getFallbackUrl() == null ? 0
                : weights.getOrDefault(route.getFallbackUrl(), 0));
        m.put("timeoutMs", route.getTimeoutMs());
        m.put("retries", route.getRetries());
        m.put("rateLimitPerMin", route.getRateLimitPerMin());
        m.put("healthPath", route.getHealthPath() == null ? "/" : route.getHealthPath());
        m.put("healthCheckEnabled", route.getHealthCheckEnabled() == null || route.getHealthCheckEnabled() != 0);
        m.put("wsMaxConnections", route.getWsMaxConnections() == null ? 100 : route.getWsMaxConnections());
        m.put("signingEnabled", route.getSigningSecret() != null && !route.getSigningSecret().isBlank());
        m.put("requestSchema", route.getRequestSchema());
        m.put("enabled", route.getEnabled() != null && route.getEnabled() == 1);
        m.put("createdAt", route.getCreatedAt());
        m.put("updatedAt", route.getUpdatedAt());
        return m;
    }

    private StreamingResponseBody error(HttpServletResponse servletResponse, HttpStatus status, String message) {
        servletResponse.setStatus(status.value());
        servletResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
        byte[] body = ("{\"code\":" + status.value() + ",\"message\":\"" + message.replace("\"", "'") + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        servletResponse.setContentLength(body.length);
        return out -> out.write(body);
    }

    private void applyHeaders(HttpServletResponse servletResponse, HttpHeaders headers, int status) {
        servletResponse.setStatus(status);
        headers.forEach((name, values) -> values.forEach(value -> servletResponse.addHeader(name, value)));
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

    private String requestSchema(Object raw) {
        String schema = text(raw, 20000);
        if (schema == null) return null;
        try {
            if (!objectMapper.readTree(schema).isObject()) throw new IllegalArgumentException("not object");
        } catch (Exception e) {
            throw new BusinessException(400, "请求体 Schema 必须是 JSON 对象");
        }
        return schema;
    }

    private String verifySignature(GatewayRoute route, HttpServletRequest request, byte[] body) {
        if (route.getSigningSecret() == null || route.getSigningSecret().isBlank()) return null;
        String secret;
        try {
            secret = cryptoService.decrypt(route.getSigningSecret());
        } catch (Exception e) {
            return "signing key invalid";
        }
        String error = GatewaySignature.verifyHeader(secret,
                request.getMethod(), request.getRequestURI(), request.getQueryString(),
                request.getHeader("X-Gateway-Timestamp"), request.getHeader("X-Gateway-Nonce"),
                body, request.getHeader("X-Gateway-Signature"));
        if (error != null) return error;
        String nonce = request.getHeader("X-Gateway-Nonce");
        try {
            String key = "gateway:nonce:" + route.getId() + ":" + nonce;
            Boolean fresh = redis.opsForValue().setIfAbsent(key, "1", GatewaySignature.nonceTtl());
            if (!Boolean.TRUE.equals(fresh)) return "replay";
        } catch (Exception e) {
            // 与限流/熔断一致：Redis 故障优先保证网关可用，nonce 唯一性降级。
            log.warn("网关 nonce 校验失败（已放行）route={}：{}", route.getId(), e.getMessage());
        }
        return null;
    }

    private String newSigningSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return "gws_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String healthPath(Object raw) {
        String value = text(raw, 512);
        if (value == null) return null;
        if (value.contains("\r") || value.contains("\n") || value.contains("://")) {
            throw new BusinessException(400, "健康检查路径格式错误");
        }
        return value.startsWith("/") ? value : "/" + value;
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
