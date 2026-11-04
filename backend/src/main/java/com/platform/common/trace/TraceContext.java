package com.platform.common.trace;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 平台统一链路上下文。
 *
 * <p>一次 HTTP 请求/一轮对话只使用一个 32 位 traceId；requestId 单独保留用于
 * 入口请求排障，不再用 requestId 冒充 traceId。</p>
 */
public final class TraceContext {

    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";
    public static final String HEADER_TRACEPARENT = "traceparent";

    private static final Pattern TRACEPARENT = Pattern.compile(
            "^[0-9a-fA-F]{2}-([0-9a-fA-F]{32})-([0-9a-fA-F]{16})-([0-9a-fA-F]{2})$");
    private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final String ZERO_TRACE = "00000000000000000000000000000000";

    private TraceContext() {
    }

    public record Context(String traceId, String requestId) {
    }

    /** 在请求入口建立上下文；支持 W3C traceparent 和 X-Request-Id 透传。 */
    public static Context start(HttpServletRequest request, HttpServletResponse response) {
        String traceId = traceIdFromTraceparent(request.getHeader(HEADER_TRACEPARENT));
        if (traceId == null) traceId = newTraceId();
        String requestId = sanitizeRequestId(request.getHeader(HEADER_REQUEST_ID));
        if (requestId == null) requestId = newTraceId();
        put(traceId, requestId);
        if (response != null) {
            response.setHeader(HEADER_TRACE_ID, traceId);
            response.setHeader(HEADER_REQUEST_ID, requestId);
        }
        return new Context(traceId, requestId);
    }

    public static String currentTraceId() {
        return MDC.get(MDC_TRACE_ID);
    }

    public static String currentRequestId() {
        return MDC.get(MDC_REQUEST_ID);
    }

    /** 没有请求上下文时（定时任务、评测等）生成一个稳定可追踪的 ID。 */
    public static String currentOrNewTraceId() {
        String current = currentTraceId();
        return current == null || current.isBlank() ? newTraceId() : current;
    }

    public static void put(String traceId, String requestId) {
        if (traceId != null && !traceId.isBlank()) MDC.put(MDC_TRACE_ID, traceId);
        if (requestId != null && !requestId.isBlank()) MDC.put(MDC_REQUEST_ID, requestId);
    }

    public static void clear() {
        MDC.remove(MDC_TRACE_ID);
        MDC.remove(MDC_REQUEST_ID);
    }

    /** 把上下文带入线程池/Reactor 回调；结束后恢复调用线程原 MDC。 */
    public static Runnable wrap(Runnable task) {
        String traceId = currentTraceId();
        String requestId = currentRequestId();
        return () -> {
            String previousTrace = currentTraceId();
            String previousRequest = currentRequestId();
            put(traceId, requestId);
            try {
                task.run();
            } finally {
                if (previousTrace == null) MDC.remove(MDC_TRACE_ID); else MDC.put(MDC_TRACE_ID, previousTrace);
                if (previousRequest == null) MDC.remove(MDC_REQUEST_ID); else MDC.put(MDC_REQUEST_ID, previousRequest);
            }
        };
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    static String traceIdFromTraceparent(String header) {
        if (header == null) return null;
        Matcher matcher = TRACEPARENT.matcher(header.trim());
        if (!matcher.matches()) return null;
        String traceId = matcher.group(1).toLowerCase();
        if (ZERO_TRACE.equals(traceId)) return null;
        return traceId;
    }

    private static String sanitizeRequestId(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return REQUEST_ID.matcher(trimmed).matches() ? trimmed : null;
    }
}
