package com.platform.gateway.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.platform.common.ratelimit.RedisRateLimiter;
import com.platform.gateway.entity.GatewayApiKey;
import com.platform.gateway.mapper.GatewayApiKeyMapper;
import com.platform.system.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 调用方 API Key 管理（评审缺口）：一个路由可发多个 Key 给不同调用方，
 * 每个 Key 独立启用/禁用、过期时间、按 Key 限流覆盖与调用统计。
 * 热路径鉴权走 Redis 缓存（60s），Key 状态变更即时清缓存。
 */
@Service
@RequiredArgsConstructor
public class GatewayApiKeyService {

    private final GatewayApiKeyMapper keyMapper;
    private final StringRedisTemplate redis;
    private final AuditService auditService;
    private final RedisRateLimiter rateLimiter;

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    public List<GatewayApiKey> list(Long routeId) {
        return keyMapper.listByRoute(routeId);
    }

    /** 脱敏视图：不回传 keyHash。 */
    public List<Map<String, Object>> listView(Long routeId) {
        return list(routeId).stream().map(this::view).toList();
    }

    public Map<String, Object> apiKeyView(GatewayApiKey k) {
        return view(k);
    }

    private Map<String, Object> view(GatewayApiKey k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.getId());
        m.put("name", k.getName());
        m.put("keyPrefix", k.getKeyPrefix());
        m.put("enabled", k.getEnabled() != null && k.getEnabled() == 1);
        m.put("expiresAt", k.getExpiresAt());
        m.put("rateLimitPerMin", k.getRateLimitPerMin());
        m.put("dailyQuota", k.getDailyQuota());
        m.put("allowedMethods", k.getAllowedMethods());
        m.put("allowedPaths", k.getAllowedPaths());
        m.put("allowedIps", k.getAllowedIps());
        m.put("requestCount", k.getRequestCount());
        m.put("lastUsedAt", k.getLastUsedAt());
        m.put("createdAt", k.getCreatedAt());
        return m;
    }

    public record CreatedKey(GatewayApiKey key, String apiKey) { }

    public CreatedKey create(Long routeId, Long tenantId, String name,
                             Integer rateLimitPerMin, java.time.LocalDateTime expiresAt, Long operatorId) {
        return create(routeId, tenantId, name, rateLimitPerMin, null, null, expiresAt, operatorId);
    }

    public CreatedKey create(Long routeId, Long tenantId, String name,
                             Integer rateLimitPerMin, String allowedMethods, String allowedPaths,
                             java.time.LocalDateTime expiresAt, Long operatorId) {
        return create(routeId, tenantId, name, rateLimitPerMin, null, allowedMethods, allowedPaths, expiresAt, operatorId);
    }

    public CreatedKey create(Long routeId, Long tenantId, String name,
                             Integer rateLimitPerMin, Integer dailyQuota, String allowedMethods, String allowedPaths,
                             java.time.LocalDateTime expiresAt, Long operatorId) {
        return create(routeId, tenantId, name, rateLimitPerMin, dailyQuota, allowedMethods, allowedPaths,
                null, expiresAt, operatorId);
    }

    public CreatedKey create(Long routeId, Long tenantId, String name,
                             Integer rateLimitPerMin, Integer dailyQuota, String allowedMethods, String allowedPaths,
                             String allowedIps, java.time.LocalDateTime expiresAt, Long operatorId) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String apiKey = "gwk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        GatewayApiKey key = new GatewayApiKey();
        key.setTenantId(tenantId);
        key.setRouteId(routeId);
        key.setName(name == null || name.isBlank() ? "调用方" : name.trim());
        key.setKeyHash(sha256(apiKey));
        key.setKeyPrefix(apiKey.substring(0, Math.min(12, apiKey.length())));
        key.setEnabled(1);
        key.setExpiresAt(expiresAt);
        key.setRateLimitPerMin(rateLimitPerMin);
        key.setDailyQuota(dailyQuota == null ? null : Math.max(0, dailyQuota));
        key.setAllowedMethods(normalizeMethods(allowedMethods));
        key.setAllowedPaths(normalizePaths(allowedPaths));
        key.setAllowedIps(normalizeIps(allowedIps));
        key.setRequestCount(0L);
        key.setCreatedBy(operatorId);
        key.setCreatedAt(LocalDateTime.now());
        keyMapper.insert(key);
        auditService.record(operatorId, "gateway.key.create", "gateway_api_key",
                String.valueOf(key.getId()), "SUCCESS", Map.of("name", key.getName()));
        return new CreatedKey(key, apiKey);
    }

    /** 调用方 Key 的方法/路径 scope；空配置表示不限制。 */
    public boolean scopeAllows(GatewayApiKey key, String method, String path) {
        if (key == null) return true;
        String methods = key.getAllowedMethods();
        if (methods != null && !methods.isBlank()) {
            boolean hit = java.util.Arrays.stream(methods.split(","))
                    .map(String::trim).filter(s -> !s.isBlank())
                    .anyMatch(s -> s.equalsIgnoreCase(method == null ? "" : method.trim()));
            if (!hit) return false;
        }
        String paths = key.getAllowedPaths();
        if (paths != null && !paths.isBlank()) {
            boolean hit = false;
            for (String prefix : paths.split("[,\n\r]+")) {
                prefix = prefix.trim();
                if (!prefix.isEmpty() && path != null && path.startsWith(prefix)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) return false;
        }
        return true;
    }

    public boolean ipAllows(GatewayApiKey key, String remoteIp) {
        if (key == null || key.getAllowedIps() == null || key.getAllowedIps().isBlank()) return true;
        if (remoteIp == null || remoteIp.isBlank()) return false;
        try {
            java.net.InetAddress remote = java.net.InetAddress.getByName(remoteIp.replaceAll("%.*$", "").replace("[", "").replace("]", ""));
            for (String raw : key.getAllowedIps().split("[,\n\r]+")) {
                String rule = raw.trim();
                if (rule.isEmpty()) continue;
                int slash = rule.indexOf('/');
                String host = slash < 0 ? rule : rule.substring(0, slash);
                java.net.InetAddress allowed = java.net.InetAddress.getByName(
                        host.replace("[", "").replace("]", ""));
                if (remote.isLoopbackAddress() && allowed.isLoopbackAddress()) return true;
                if (slash < 0) {
                    if (remote.equals(allowed)) return true;
                } else {
                    int prefix = Integer.parseInt(rule.substring(slash + 1));
                    byte[] r = remote.getAddress();
                    byte[] a = allowed.getAddress();
                    if (r.length != a.length || prefix < 0 || prefix > r.length * 8) continue;
                    int full = prefix / 8;
                    int rem = prefix % 8;
                    boolean match = true;
                    for (int i = 0; i < full; i++) if (r[i] != a[i]) { match = false; break; }
                    if (match && rem > 0) {
                        int mask = (0xff << (8 - rem)) & 0xff;
                        match = (r[full] & mask) == (a[full] & mask);
                    }
                    if (match) return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private String normalizeIps(String value) {
        if (value == null || value.isBlank()) return null;
        String ips = java.util.Arrays.stream(value.split("[,\n\r]+"))
                .map(String::trim).filter(s -> !s.isBlank()).distinct()
                .collect(java.util.stream.Collectors.joining(","));
        return ips.isBlank() ? null : ips;
    }

    private String normalizeMethods(String value) {
        if (value == null || value.isBlank()) return null;
        String methods = java.util.Arrays.stream(value.split(","))
                .map(String::trim).map(String::toUpperCase)
                .filter(s -> Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS").contains(s))
                .distinct().collect(java.util.stream.Collectors.joining(","));
        return methods.isBlank() ? null : methods;
    }

    private String normalizePaths(String value) {
        if (value == null || value.isBlank()) return null;
        String paths = java.util.Arrays.stream(value.split("[,\n\r]+"))
                .map(String::trim).filter(s -> s.startsWith("/"))
                .distinct().collect(java.util.stream.Collectors.joining(","));
        return paths.isBlank() ? null : paths;
    }

    /** 禁用/启用/改名/改限流/改过期（部分更新，空值保留）。 */
    public void update(Long keyId, String name, Boolean enabled,
                       Integer rateLimitPerMin, LocalDateTime expiresAt, Long operatorId) {
        update(keyId, name, enabled, rateLimitPerMin, null, null, expiresAt, operatorId);
    }

    public void update(Long keyId, String name, Boolean enabled,
                       Integer rateLimitPerMin, String allowedMethods, String allowedPaths,
                       LocalDateTime expiresAt, Long operatorId) {
        update(keyId, name, enabled, rateLimitPerMin, null, allowedMethods, allowedPaths, expiresAt, operatorId);
    }

    public void update(Long keyId, String name, Boolean enabled,
                       Integer rateLimitPerMin, Integer dailyQuota, String allowedMethods, String allowedPaths,
                       LocalDateTime expiresAt, Long operatorId) {
        update(keyId, name, enabled, rateLimitPerMin, dailyQuota, allowedMethods, allowedPaths,
                null, expiresAt, operatorId);
    }

    public void update(Long keyId, String name, Boolean enabled,
                       Integer rateLimitPerMin, Integer dailyQuota, String allowedMethods, String allowedPaths,
                       String allowedIps, LocalDateTime expiresAt, Long operatorId) {
        GatewayApiKey key = keyMapper.selectById(keyId);
        if (key == null) return;
        GatewayApiKey patch = new GatewayApiKey();
        patch.setId(keyId);
        if (name != null && !name.isBlank()) patch.setName(name.trim());
        if (enabled != null) patch.setEnabled(enabled ? 1 : 0);
        if (rateLimitPerMin != null) patch.setRateLimitPerMin(Math.max(0, rateLimitPerMin));
        if (dailyQuota != null) patch.setDailyQuota(Math.max(0, dailyQuota));
        if (allowedMethods != null) patch.setAllowedMethods(normalizeMethods(allowedMethods));
        if (allowedPaths != null) patch.setAllowedPaths(normalizePaths(allowedPaths));
        if (allowedIps != null) patch.setAllowedIps(normalizeIps(allowedIps));
        if (expiresAt != null) patch.setExpiresAt(expiresAt);
        keyMapper.updateById(patch);
        evict(key.getKeyHash());
        auditService.record(operatorId, "gateway.key.update", "gateway_api_key",
                String.valueOf(keyId), "SUCCESS", Map.of());
    }

    public void delete(Long keyId, Long operatorId) {
        GatewayApiKey key = keyMapper.selectById(keyId);
        if (key == null) return;
        keyMapper.deleteById(keyId);
        evict(key.getKeyHash());
        auditService.record(operatorId, "gateway.key.delete", "gateway_api_key",
                String.valueOf(keyId), "SUCCESS", Map.of("name", key.getName()));
    }

    public void evictByRawKey(String rawKey) {
        evict(sha256(rawKey == null ? "" : rawKey));
    }

    private void evict(String keyHash) {
        try { redis.delete("gateway:keyhash:" + keyHash); } catch (Exception ignored) { }
    }

    // ---------- 代理热路径鉴权/限流（供 GatewayService 调用） ----------

    public String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 哈希命中已启用且未过期的 Key（Redis 缓存 60s；禁用/删除即时失效）。 */
    public GatewayApiKey matchEnabledKey(String rawKey) {
        String keyHash = sha256(rawKey == null ? "" : rawKey.trim());
        String cached = null;
        try { cached = redis.opsForValue().get("gateway:keyhash:" + keyHash); } catch (Exception ignored) { }
        if ("DISABLED".equals(cached)) return null;
        GatewayApiKey key = null;
        if (cached != null) {
            try {
                key = new com.fasterxml.jackson.databind.ObjectMapper().readValue(cached, GatewayApiKey.class);
            } catch (Exception e) { return null; }
        } else {
            key = keyMapper.findByHash(keyHash);
            if (key == null || key.getEnabled() == null || key.getEnabled() != 1) {
                try {
                    redis.opsForValue().set("gateway:keyhash:" + keyHash, "DISABLED",
                            Duration.ofSeconds(CACHE_TTL.toSeconds()));
                } catch (Exception ignored) { }
                return null;
            }
            try {
                redis.opsForValue().set("gateway:keyhash:" + keyHash,
                        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(key), CACHE_TTL);
            } catch (Exception ignored) { }
        }
        if (key.getExpiresAt() != null && key.getExpiresAt().isBefore(LocalDateTime.now())) return null;
        return key;
    }

    /** 按 Key 限流（未配置 = 路由级限流兜底）。 */
    public boolean allowByKey(GatewayApiKey key) {
        if (key.getRateLimitPerMin() == null || key.getRateLimitPerMin() <= 0) return true;
        String bucket = "ratelimit:gwk:" + key.getId() + ":" + (System.currentTimeMillis() / 60_000);
        return rateLimiter.allow(bucket, key.getRateLimitPerMin());
    }

    /** 按 Key 每日配额；0/NULL 不限制，Redis 异常 fail-open。 */
    public boolean allowDaily(GatewayApiKey key) {
        if (key.getDailyQuota() == null || key.getDailyQuota() <= 0) return true;
        try {
            String day = java.time.LocalDate.now().toString();
            String bucket = "gateway:keydaily:" + key.getId() + ":" + day;
            Long count = redis.opsForValue().increment(bucket);
            if (count != null && count == 1L) redis.expire(bucket, Duration.ofDays(2));
            return count == null || count <= key.getDailyQuota();
        } catch (Exception e) {
            return true;
        }
    }

    public void markUsed(GatewayApiKey key) {
        try {
            com.platform.common.context.TenantContext.run(key.getTenantId(), () -> {
                keyMapper.update(null, new LambdaUpdateWrapper<GatewayApiKey>()
                        .eq(GatewayApiKey::getId, key.getId())
                        .setSql("request_count = request_count + 1, last_used_at = NOW()"));
            });
        } catch (Exception ignored) { }
    }
}
