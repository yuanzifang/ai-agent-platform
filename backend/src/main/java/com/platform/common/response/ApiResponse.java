package com.platform.common.response;

import org.slf4j.MDC;

/**
 * 统一响应（需求文档 7.1）：code 0 为成功，业务错误码见需求文档 7.1 业务错误码段。
 */
public record ApiResponse<T>(int code, String message, T data, String traceId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "success", data, MDC.get("traceId"));
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, MDC.get("traceId"));
    }
}
