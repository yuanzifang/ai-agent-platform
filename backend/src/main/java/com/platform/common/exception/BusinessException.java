package com.platform.common.exception;

/**
 * 业务异常：code 使用 HTTP 语义码或需求文档 7.1 的 6 位业务码。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
