package com.platform.common.config;

import com.platform.common.context.TenantContext;
import com.platform.common.ratelimit.RedisRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 租户级接口限流（需求 3.22）：Redis 分钟桶。
 * 限流值优先取租户配额 sys_tenant_quota.max_api_calls_per_min（此前只读全局
 * 固定值，配额页面配置的每租户限流不生效）；未配配额回落全局值；0/关闭不限。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RedisRateLimiter rateLimiter;

    @Autowired
    public RateLimitInterceptor(RedisRateLimiter rateLimiter,
                                @Value("${security.rate-limit-per-minute:60}") int defaultLimit) {
        this.rateLimiter = rateLimiter;
        this.defaultLimit = defaultLimit;
    }

    private final int defaultLimit;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (defaultLimit <= 0) return true;
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) return true;

        // 租户配额值（Redis 缓存）优先；-1 = 未配置配额，回落全局默认
        int limit = rateLimiter.tenantLimitPerMinute(tenantId);
        if (limit < 0) limit = defaultLimit;

        String bucket = "ratelimit:t:" + tenantId + ":" + (System.currentTimeMillis() / 60_000);
        if (!rateLimiter.allow(bucket, limit)) {
            response.setStatus(429);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"code\":429,\"message\":\"请求过于频繁，已触发租户限流（" + limit + "/分钟）\",\"data\":null}");
            return false;
        }
        return true;
    }
}
