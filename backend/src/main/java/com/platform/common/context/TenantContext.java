package com.platform.common.context;

import com.alibaba.ttl.TransmittableThreadLocal;

/**
 * 租户上下文（架构 2.2）：HTTP 请求经 TenantInterceptor 注入；异步线程经 TTL 传递（架构 2.3）。
 */
public final class TenantContext {

    private static final TransmittableThreadLocal<Long> TENANT = new TransmittableThreadLocal<>();

    private TenantContext() {
    }

    public static void setTenantId(Long tenantId) {
        TENANT.set(tenantId);
    }

    public static Long getTenantId() {
        return TENANT.get();
    }

    public static void clear() {
        TENANT.remove();
    }

    /** 在异步线程（SSE 回调/Reactor 调度）中以指定租户执行，结束恢复原值。 */
    public static void run(Long tenantId, Runnable action) {
        Long previous = TENANT.get();
        TENANT.set(tenantId);
        try {
            action.run();
        } finally {
            if (previous != null) TENANT.set(previous);
            else TENANT.remove();
        }
    }

    /** 同 {@link #run}，但携带返回值（分享链接免登录场景）。 */
    public static <T> T runWith(Long tenantId, java.util.function.Supplier<T> action) {
        Long previous = TENANT.get();
        TENANT.set(tenantId);
        try {
            return action.get();
        } finally {
            if (previous != null) TENANT.set(previous);
            else TENANT.remove();
        }
    }
}
