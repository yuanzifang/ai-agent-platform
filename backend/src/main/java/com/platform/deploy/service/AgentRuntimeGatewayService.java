package com.platform.deploy.service;

import com.platform.agent.entity.AgentRegistry;
import com.platform.agent.entity.IntegrationApiKey;
import com.platform.agent.mapper.AgentRegistryMapper;
import com.platform.agent.mapper.IntegrationApiKeyMapper;
import com.platform.common.context.TenantContext;
import com.platform.common.exception.BusinessException;
import com.platform.common.security.CryptoService;
import com.platform.deploy.entity.AgentDeployment;
import com.platform.deploy.entity.AgentRuntimeKey;
import com.platform.deploy.mapper.AgentDeploymentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 平台侧 Agent 运行时网关：承接原 Node agent-gateway 的 Bearer/调用方 Key、
 * 分钟限流、日配额、用量统计；nginx 只做入口和反向代理。
 *
 * 关键点：
 * - 运行时部署令牌来自 agent_deployment（加密存储），调用方 Key 来自 agent_runtime_key；
 * - 鉴权/配额不依赖容器内明文 Key；
 * - 多副本共享状态优先走 Redis，Redis 不可用时回退本机内存（fail-open）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRuntimeGatewayService {

    private static final String DAY_PREFIX = "agent-runtime";
    private static final Duration STATS_TTL = Duration.ofDays(7);
    private static final Duration QUOTA_TTL = Duration.ofHours(26);

    /** 与 Node 运行时一致的 Redis 令牌桶，保证多副本共享限流。 */
    private static final DefaultRedisScript<Long> RATE_SCRIPT = new DefaultRedisScript<>("""
            local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens'))
            local last = tonumber(redis.call('HGET', KEYS[1], 'last'))
            local capacity = tonumber(ARGV[1])
            local ratePerMs = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            if tokens == nil then tokens = capacity last = now end
            tokens = math.min(capacity, tokens + (now - last) * ratePerMs)
            local allowed = 0
            if tokens >= 1 then tokens = tokens - 1 allowed = 1 end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'last', now)
            redis.call('PEXPIRE', KEYS[1], 120000)
            return allowed
            """, Long.class);

    private final AgentDeploymentMapper deploymentMapper;
    private final AgentRuntimeKeyService runtimeKeyService;
    private final AgentRegistryMapper agentMapper;
    private final IntegrationApiKeyMapper integrationApiKeyMapper;
    private final CryptoService cryptoService;
    private final StringRedisTemplate redis;

    @Value("${agent.deploy.rate-limit-per-min:60}")
    private int rateLimitPerMin;

    @Value("${agent.deploy.rate-limit-burst:20}")
    private int rateLimitBurst;

    @Value("${agent.deploy.caller-quota-daily:0}")
    private int callerQuotaDaily;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Map<String, Usage> usage = new ConcurrentHashMap<>();

    public record Caller(String id, String name, int dailyQuota, Long tenantId) { }

    /** 鉴权入口；先验证令牌，再判断运行状态，避免未认证请求探测部署状态。 */
    public Caller authenticate(Long agentId, String authorization, boolean requireRunning) {
        AgentDeployment deployment = deploymentMapper.findByAgentIdIgnore(agentId);
        if (deployment == null) throw new BusinessException(404, "该 Agent 尚未部署");
        String raw = bearer(authorization);
        if (raw == null) throw new BusinessException(401, "缺少 Bearer 令牌");

        String runtimeToken = cryptoService.decrypt(deployment.getRuntimeToken());
        if (runtimeToken != null && !runtimeToken.isBlank() && safeEqual(raw, runtimeToken)) {
            return checkedCaller(new Caller("default", "deployment token",
                    nonNegative(callerQuotaDaily), deployment.getTenantId()), deployment, requireRunning);
        }
        String hash = sha256(raw);
        List<AgentRuntimeKey> keys = TenantContext.runWith(deployment.getTenantId(),
                () -> runtimeKeyService.enabledKeys(agentId));
        for (AgentRuntimeKey key : keys) {
            String expected = key.getKeyHash();
            if (expected != null && safeEqual(hash, expected.toLowerCase())) {
                String callerId = expected.length() >= 12 ? expected.substring(0, 12) : expected;
                return checkedCaller(new Caller(callerId, key.getName(),
                        key.getDailyQuota() == null ? 0 : Math.max(0, key.getDailyQuota()),
                        deployment.getTenantId()), deployment, requireRunning);
            }
        }
        throw new BusinessException(401, "无效的 Bearer 令牌");
    }

    private Caller checkedCaller(Caller caller, AgentDeployment deployment, boolean requireRunning) {
        if (requireRunning && !"RUNNING".equalsIgnoreCase(deployment.getStatus())) {
            throw new BusinessException(409, "Agent 运行时未运行");
        }
        return caller;
    }

    public AgentRegistry agent(Long agentId) {
        AgentDeployment deployment = requireDeployment(agentId);
        AgentRegistry agent = TenantContext.runWith(deployment.getTenantId(),
                () -> agentMapper.selectById(agentId));
        if (agent == null || "DELETED".equals(agent.getStatus())) {
            throw new BusinessException(404, "Agent 不存在");
        }
        if (!"PUBLISHED".equals(agent.getStatus())) {
            throw new BusinessException(403, "Agent 未发布，无法调用");
        }
        return agent;
    }

    /** 运行时内部使用的集成 Key；明文不进入容器，也不下发给 nginx。 */
    public IntegrationApiKey integrationKey(Long agentId) {
        AgentDeployment deployment = requireDeployment(agentId);
        if (deployment.getApiKeyId() == null) {
            throw new BusinessException(409, "运行时缺少平台集成 Key，请重新部署");
        }
        IntegrationApiKey key = integrationApiKeyMapper.findByIdIgnore(deployment.getApiKeyId());
        if (key == null || !"ENABLED".equals(key.getStatus())) {
            throw new BusinessException(409, "运行时集成 Key 不可用，请重新部署");
        }
        if (!agentId.equals(key.getAgentId())) {
            throw new BusinessException(409, "运行时集成 Key 与 Agent 不匹配");
        }
        return key;
    }

    /** 限流 + 日配额 + 用量记录；失败时抛 429。 */
    public void beforeRequest(Long agentId, Caller caller, boolean stream) {
        if (!allowRate(agentId, caller.id())) {
            throw new BusinessException(429, "运行时限流（" + Math.max(0, rateLimitPerMin) + "/分钟）");
        }
        if (!reserveQuota(agentId, caller)) {
            throw new BusinessException(429, "运行时日配额已用尽（" + caller.dailyQuota() + " 次/天）");
        }
        recordUsage(agentId, caller.id(), stream);
    }

    public Map<String, Object> stats(Long agentId) {
        AgentDeployment deployment = deploymentMapper.findByAgentIdIgnore(agentId);
        if (deployment == null) throw new BusinessException(404, "该 Agent 尚未部署");
        String day = day();
        List<Map<String, Object>> callers = new ArrayList<>();
        String store = "memory";

        if (redis != null) {
            try {
                Map<Object, Object> counts = redis.opsForHash()
                        .entries(statsKey(agentId, day));
                Map<Object, Object> streams = redis.opsForHash()
                        .entries(streamsKey(agentId, day));
                for (Map.Entry<Object, Object> e : counts.entrySet()) {
                    String callerId = String.valueOf(e.getKey());
                    callers.add(callerView(agentId, callerId,
                            longValue(e.getValue()), longValue(streams.get(callerId))));
                }
                store = "redis";
            } catch (Exception e) {
                log.warn("读取 Agent 运行时 Redis 用量失败，回退内存：agent={} err={}", agentId, e.getMessage());
            }
        }
        if ("memory".equals(store)) {
            String prefix = agentId + "|";
            for (Map.Entry<String, Usage> e : usage.entrySet()) {
                if (!e.getKey().startsWith(prefix)) continue;
                Usage row = e.getValue();
                if (!day.equals(row.day)) continue;
                long requests;
                long streams;
                synchronized (row) {
                    requests = row.count;
                    streams = row.streams;
                }
                callers.add(callerView(agentId, row.callerId, requests, streams));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("day", day);
        result.put("runtime", "platform");
        result.put("rateLimitPerMin", Math.max(0, rateLimitPerMin));
        result.put("burst", Math.max(0, rateLimitBurst));
        result.put("callers", callers);
        result.put("store", store);
        return result;
    }

    private Map<String, Object> callerView(Long agentId, String callerId, long requests, long streams) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("caller", callerId);
        row.put("name", callerName(agentId, callerId));
        row.put("requests", requests);
        row.put("streams", streams);
        return row;
    }

    private String callerName(Long agentId, String callerId) {
        if ("default".equals(callerId)) return "deployment token";
        try {
            AgentDeployment deployment = deploymentMapper.findByAgentIdIgnore(agentId);
            if (deployment != null) {
                List<AgentRuntimeKey> keys = TenantContext.runWith(deployment.getTenantId(),
                        () -> runtimeKeyService.enabledKeys(agentId));
                for (AgentRuntimeKey key : keys) {
                    String hash = key.getKeyHash();
                    if (hash != null && hash.startsWith(callerId)) return key.getName();
                }
            }
        } catch (Exception ignored) {
            // 已删除的 Key 仍保留统计中的 caller id
        }
        return callerId;
    }

    private AgentDeployment requireDeployment(Long agentId) {
        AgentDeployment deployment = deploymentMapper.findByAgentIdIgnore(agentId);
        if (deployment == null) throw new BusinessException(404, "该 Agent 尚未部署");
        return deployment;
    }

    private boolean allowRate(Long agentId, String callerId) {
        int limit = Math.max(0, rateLimitPerMin);
        if (limit <= 0) return true;
        int burst = Math.max(0, rateLimitBurst);
        String key = "agent-runtime:rate:" + agentId + ":" + callerId;
        if (redis != null) {
            try {
                Long allowed = redis.execute(RATE_SCRIPT, List.of(key),
                        String.valueOf(burst + 1), String.valueOf(limit / 60000.0),
                        String.valueOf(System.currentTimeMillis()));
                if (allowed != null) return allowed == 1L;
            } catch (Exception e) {
                log.warn("Agent 运行时 Redis 限流失败，回退内存：{}", e.getMessage());
            }
        }
        Bucket bucket = buckets.computeIfAbsent(key,
                ignored -> new Bucket(burst + 1, System.currentTimeMillis()));
        synchronized (bucket) {
            long now = System.currentTimeMillis();
            double refillPerMs = limit / 60000.0;
            bucket.tokens = Math.min(burst + 1.0, bucket.tokens + (now - bucket.last) * refillPerMs);
            bucket.last = now;
            if (bucket.tokens < 1.0) return false;
            bucket.tokens -= 1.0;
            return true;
        }
    }

    private boolean reserveQuota(Long agentId, Caller caller) {
        int quota = Math.max(0, caller.dailyQuota());
        if (quota <= 0) return true;
        String day = day();
        if (redis != null) {
            try {
                String key = "agent-runtime:quota:" + agentId + ":" + caller.id() + ":" + day;
                Long count = redis.opsForValue().increment(key);
                if (count != null && count == 1L) redis.expire(key, QUOTA_TTL);
                if (count != null && count > quota) {
                    redis.opsForValue().decrement(key);
                    return false;
                }
                return true;
            } catch (Exception e) {
                log.warn("Agent 运行时 Redis 配额失败，回退内存：{}", e.getMessage());
            }
        }
        Usage row = localUsage(agentId, caller.id(), day);
        synchronized (row) {
            return row.count < quota;
        }
    }

    private void recordUsage(Long agentId, String callerId, boolean stream) {
        String day = day();
        Usage row = localUsage(agentId, callerId, day);
        synchronized (row) {
            row.count++;
            if (stream) row.streams++;
        }
        if (redis == null) return;
        try {
            redis.opsForHash().increment(statsKey(agentId, day), callerId, 1);
            redis.expire(statsKey(agentId, day), STATS_TTL);
            if (stream) {
                redis.opsForHash().increment(streamsKey(agentId, day), callerId, 1);
                redis.expire(streamsKey(agentId, day), STATS_TTL);
            }
        } catch (Exception e) {
            log.warn("Agent 运行时 Redis 用量写入失败：{}", e.getMessage());
        }
    }

    private Usage localUsage(Long agentId, String callerId, String day) {
        String key = agentId + "|" + callerId + "|" + day;
        return usage.computeIfAbsent(key, ignored -> new Usage(callerId, day));
    }

    private String statsKey(Long agentId, String day) {
        return DAY_PREFIX + ":stats:" + agentId + ":" + day;
    }

    private String streamsKey(Long agentId, String day) {
        return DAY_PREFIX + ":streams:" + agentId + ":" + day;
    }

    private String day() {
        return LocalDate.now(ZoneOffset.UTC).toString().replace("-", "");
    }

    private String bearer(String authorization) {
        if (authorization == null || authorization.isBlank()) return null;
        String value = authorization.trim();
        if (!value.toLowerCase().startsWith("bearer ")) return null;
        String token = value.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private boolean safeEqual(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BusinessException(500, "运行时令牌摘要失败");
        }
    }

    private int nonNegative(int value) {
        return Math.max(0, value);
    }

    private long longValue(Object value) {
        if (value instanceof Number n) return n.longValue();
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static final class Bucket {
        private double tokens;
        private long last;

        private Bucket(double tokens, long last) {
            this.tokens = tokens;
            this.last = last;
        }
    }

    private static final class Usage {
        private final String callerId;
        private final String day;
        private long count;
        private long streams;

        private Usage(String callerId, String day) {
            this.callerId = callerId;
            this.day = day;
        }
    }
}
