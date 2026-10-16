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
}
