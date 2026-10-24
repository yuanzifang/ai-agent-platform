package com.platform.common.ratelimit;

import com.platform.system.entity.SysTenantQuota;
import com.platform.system.mapper.SysTenantQuotaMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 分钟桶限流 + 轻量熔断状态（供拦截器/开放 API/模型路由共用）。
 * 约定：Redis 故障时限流 fail-open（可用性优先），熔断状态读写失败按未熔断处理。
 */
@Component
@RequiredArgsConstructor
public class RedisRateLimiter {

    private final StringRedisTemplate redis;
    private final SysTenantQuotaMapper quotaMapper;

    /** 租户每分钟 API 限流值：优先 sys_tenant_quota（Redis 缓存 5 分钟）；无配额记录返回 -1 表示用全局值。 */
    public int tenantLimitPerMinute(Long tenantId) {
        if (tenantId == null) return -1;
        String cacheKey = "quota:apilimit:" + tenantId;
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) return Integer.parseInt(cached);
        } catch (Exception ignored) { }
        // 调用方可能无租户上下文（OpenAPI X-Api-Key 场景），显式还原后查询
        SysTenantQuota q = com.platform.common.context.TenantContext.runWith(tenantId, () ->
                quotaMapper.selectOne(new LambdaQueryWrapper<SysTenantQuota>()
                        .eq(SysTenantQuota::getTenantId, tenantId)));
        int limit = (q == null || q.getMaxApiCallsPerMin() == null) ? -1 : q.getMaxApiCallsPerMin();
        try {
            redis.opsForValue().set(cacheKey, String.valueOf(limit), Duration.ofMinutes(5));
        } catch (Exception ignored) { }
        return limit;
    }

    /** 分钟桶计数，超过 limit 返回 false；Redis 故障 fail-open。 */
    public boolean allow(String bucket, int limit) {
        if (limit <= 0) return true;
        try {
            Long count = redis.opsForValue().increment(bucket);
            if (count != null && count == 1L) redis.expire(bucket, Duration.ofSeconds(70));
            return count == null || count <= limit;
        } catch (Exception e) {
            return true;
        }
    }

    // ---------- 模型轻量熔断（连续失败计数 → 冷却期内直接走备用） ----------

    private static final int FAIL_THRESHOLD = 3;
    private static final Duration FAIL_WINDOW = Duration.ofMinutes(5);
    private static final Duration COOLDOWN = Duration.ofSeconds(60);

    public void recordModelFailure(String model) {
        if (model == null || model.isBlank()) return;
        try {
            String failKey = "model:fail:" + model;
            Long n = redis.opsForValue().increment(failKey);
            if (n != null && n == 1L) redis.expire(failKey, FAIL_WINDOW);
            if (n != null && n >= FAIL_THRESHOLD) {
                redis.opsForValue().set("model:cooldown:" + model, "1", COOLDOWN);
            }
        } catch (Exception ignored) { }
    }

    public void recordModelSuccess(String model) {
        if (model == null || model.isBlank()) return;
        try {
            redis.delete("model:fail:" + model);
            redis.delete("model:cooldown:" + model);
        } catch (Exception ignored) { }
    }

    public boolean modelCooling(String model) {
        if (model == null || model.isBlank()) return false;
        try {
            return Boolean.TRUE.equals(redis.hasKey("model:cooldown:" + model));
        } catch (Exception e) {
            return false;
        }
    }
}
