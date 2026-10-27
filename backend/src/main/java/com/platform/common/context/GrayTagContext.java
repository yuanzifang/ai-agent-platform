package com.platform.common.context;

/**
 * 灰度标记请求上下文（需求：灰度从网关入口标记，全链路透传）。
 * 入口（统一 API 网关转发 / Open API / Web 对话）解析 X-Gray-Tag 后 set；
 * Trace/审计等环节 get 读取；请求结束必须 clear（与 TenantContext 同为 ThreadLocal 语义）。
 * 标记本身不做路由决策，仅用于全链路可见（Trace tags、审计），路由消费为后续迭代。
 */
public final class GrayTagContext {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private GrayTagContext() {
    }

    public static void set(String tag) {
        HOLDER.set(tag);
    }

    /** 当前请求的灰度标记；未标记时返回 null。 */
    public static String get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
