package com.platform.common.config;

import com.platform.common.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

/**
 * 租户级接口限流（需求 3.22/配额 maxApiCallsPerMin）：Redis 固定窗口，按 分钟桶 计数。
 * 未认证请求（无租户上下文）不限流；/auth/login 由登录自身策略保护。
 */
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate redis;

    @Value("${security.rate-limit-per-minute:60}")
    private int limitPerMinute;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (limitPerMinute <= 0) return true;
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) return true;

        String bucket = "ratelimit:t:" + tenantId + ":" + (System.currentTimeMillis() / 60_000);
        Long count = redis.opsForValue().increment(bucket);
        if (count != null && count == 1L) {
            redis.expire(bucket, Duration.ofSeconds(70));
        }
        if (count != null && count > limitPerMinute) {
            response.setStatus(429);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"code\":429,\"message\":\"请求过于频繁，已触发租户限流（" + limitPerMinute + "/分钟）\",\"data\":null}");
            return false;
        }
        return true;
    }
}
