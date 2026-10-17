package com.platform.common.exception;

import com.platform.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理（需求文档 7.1）：HTTP 状态码用语义码；响应体 code 携带 6 位业务码或语义码。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        int code = e.getCode();
        // 语义码直接作为 HTTP 状态；6 位业务码默认映射 400
        int httpStatus = (code >= 400 && code < 600) ? code : 400;
        return ResponseEntity.status(httpStatus).body(ApiResponse.error(code, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse("参数错误");
        return ResponseEntity.badRequest().body(ApiResponse.error(400, msg));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(404).body(ApiResponse.error(404, "接口不存在"));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException e) {
        return ResponseEntity.status(403).body(ApiResponse.error(403, "无操作权限（权限点校验未通过）"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e) {
        // MyBatis/代理层可能把 BusinessException 包装在 cause 链中，先解包还原语义
        BusinessException business = findBusiness(e);
        if (business != null) {
            int http = (business.getCode() >= 400 && business.getCode() < 600) ? business.getCode() : 400;
            return ResponseEntity.status(http).body(ApiResponse.error(business.getCode(), business.getMessage()));
        }
        log.error("unhandled exception", e);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.error(500, "系统异常，请稍后重试"));
    }

    private BusinessException findBusiness(Throwable t) {
        while (t != null) {
            if (t instanceof BusinessException be) return be;
            t = t.getCause();
        }
        return null;
    }
}
