package com.platform.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.security.NetworkPolicyService;
import com.platform.gateway.entity.GatewayRoute;
import com.platform.gateway.mapper.GatewayRouteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关上游主动健康检查：定时探测启用路由的每个上游，状态写入 Redis，供代理跳过失败上游。
 * 探测失败/Redis 异常时 fail-open，不阻断代理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayHealthService {

    private static final String KEY_PREFIX = "gateway:health:v1:";
    private static final String DOWN = "DOWN";
    private static final String UP = "UP";
    private static final String UNKNOWN = "UNKNOWN";
    private static final String DISABLED = "DISABLED";

    private final GatewayRouteMapper routeMapper;
    private final StringRedisTemplate redis;
    private final NetworkPolicyService networkPolicy;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Value("${gateway.health.enabled:true}")
    private boolean enabled;

    @Value("${gateway.health.interval-ms:30000}")
    private long intervalMs;

    @Value("${gateway.health.timeout-ms:3000}")
    private long timeoutMs;

    public record Status(String upstream, String status, Integer statusCode,
                         long latencyMs, long checkedAt, String error) {
    }

    @Scheduled(fixedDelayString = "${gateway.health.interval-ms:30000}",
            initialDelayString = "${gateway.health.initial-delay-ms:10000}")
    public void scan() {
        if (!enabled) return;
        List<GatewayRoute> routes;
        try {
            routes = routeMapper.listEnabledIgnore();
        } catch (Exception e) {
            log.debug("网关健康检查扫描失败：{}", e.getMessage());
            return;
        }
        for (GatewayRoute route : routes) {
            if (route.getHealthCheckEnabled() != null && route.getHealthCheckEnabled() == 0) continue;
            for (String target : targets(route)) {
                try {
                    check(route, target);
                } catch (Exception e) {
                    log.debug("网关健康检查失败 route={} upstream={}：{}",
                            route.getId(), target, e.getMessage());
                }
            }
        }
    }

    /** 手动触发整条路由检查，返回最新状态。 */
    public List<Map<String, Object>> checkRoute(GatewayRoute route) {
        for (String target : targets(route)) {
            check(route, target);
        }
        return view(route);
    }

    /** 供代理判断是否跳过该上游；没有状态/Redis 异常一律视为可用。 */
    public boolean isUnhealthy(Long routeId, String upstream) {
        Status status = read(routeId, upstream);
        return status != null && DOWN.equalsIgnoreCase(status.status());
    }

    /** 一次请求批量读取不可用上游，减少代理热路径 Redis 访问。 */
    public java.util.Set<String> unhealthyTargets(Long routeId, List<String> targets) {
        java.util.Set<String> down = new java.util.HashSet<>();
        for (String target : targets) {
            if (isUnhealthy(routeId, target)) down.add(target);
        }
        return down;
    }

    /** 清理路由的健康状态；路由删除时调用。 */
    public void clearRoute(Long routeId) {
        try {
            var keys = redis.keys(KEY_PREFIX + routeId + ":*");
            if (keys != null && !keys.isEmpty()) redis.delete(keys);
        } catch (Exception e) {
            log.debug("网关健康状态清理失败 route={}：{}", routeId, e.getMessage());
        }
    }

    /** 返回所有上游的状态；未探测显示 UNKNOWN，关闭检查显示 DISABLED。 */
    public List<Map<String, Object>> view(GatewayRoute route) {
        boolean checkEnabled = route.getHealthCheckEnabled() == null || route.getHealthCheckEnabled() != 0;
        List<Map<String, Object>> result = new ArrayList<>();
        for (String target : targets(route)) {
            Status status = read(route.getId(), target);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("upstream", target);
            row.put("healthPath", healthPath(route));
            row.put("status", !checkEnabled ? DISABLED
                    : status == null ? UNKNOWN : status.status());
            row.put("statusCode", status == null ? null : status.statusCode());
            row.put("latencyMs", status == null ? null : status.latencyMs());
            row.put("checkedAt", status == null ? null : status.checkedAt());
            row.put("error", status == null ? null : status.error());
            result.add(row);
        }
        return result;
    }

    private void check(GatewayRoute route, String upstream) {
        long start = System.currentTimeMillis();
        String url = healthUrl(upstream, healthPath(route));
        Status status;
        try {
            networkPolicy.validateHttpUrl(url, "健康检查地址");
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(timeoutMs, 500)))
                    .header("User-Agent", "ai-agent-gateway-health/1.0")
                    .GET().build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            status = new Status(upstream, healthy(code) ? UP : DOWN, code,
                    System.currentTimeMillis() - start, System.currentTimeMillis(), null);
        } catch (Exception e) {
            status = new Status(upstream, DOWN, null,
                    System.currentTimeMillis() - start, System.currentTimeMillis(), root(e));
        }
        write(route.getId(), upstream, status);
    }

    boolean healthy(int statusCode) {
        // 4xx 说明端口/应用可达，只有 5xx 视为上游不健康。
        return statusCode < 500;
    }

    String healthPath(GatewayRoute route) {
        String path = route.getHealthPath();
        if (path == null || path.isBlank()) return "/";
        path = path.trim();
        return path.startsWith("/") ? path : "/" + path;
    }

    String healthUrl(String target, String path) {
        String base = target == null ? "" : target.replaceAll("/+$", "");
        return base + path;
    }

    private void write(Long routeId, String upstream, Status status) {
        if (routeId == null) return;
        try {
            redis.opsForValue().set(key(routeId, upstream), objectMapper.writeValueAsString(status),
                    Duration.ofMillis(Math.max(intervalMs * 3, 60_000)));
        } catch (Exception e) {
            log.debug("网关健康状态写入失败 route={} upstream={}：{}",
                    routeId, upstream, e.getMessage());
        }
    }

    private Status read(Long routeId, String upstream) {
        if (routeId == null) return null;
        try {
            String raw = redis.opsForValue().get(key(routeId, upstream));
            return raw == null || raw.isBlank() ? null : objectMapper.readValue(raw, Status.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String key(Long routeId, String upstream) {
        return KEY_PREFIX + routeId + ":" + Integer.toHexString(String.valueOf(upstream).hashCode());
    }

    private List<String> targets(GatewayRoute route) {
        List<String> result = new ArrayList<>();
        if (route.getTargetUrl() != null && !route.getTargetUrl().isBlank()) result.add(route.getTargetUrl());
        if (route.getFallbackUrl() != null && !route.getFallbackUrl().isBlank()) result.add(route.getFallbackUrl());
        String extra = route.getUpstreamUrls();
        if (extra != null && !extra.isBlank()) {
            try {
                if (extra.trim().startsWith("[")) {
                    for (Object value : objectMapper.readValue(extra, List.class)) {
                        String target = String.valueOf(value).trim().replaceAll("/+$", "");
                        if (!target.isBlank() && !result.contains(target)) result.add(target);
                    }
                } else {
                    for (String value : extra.split(",")) {
                        String target = value.trim().replaceAll("/+$", "");
                        if (!target.isBlank() && !result.contains(target)) result.add(target);
                    }
                }
            } catch (Exception e) {
                log.debug("网关健康检查上游列表解析失败 route={}：{}", route.getId(), e.getMessage());
            }
        }
        return result;
    }

    private String root(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }
}
