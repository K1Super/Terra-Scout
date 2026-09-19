package com.terrascout.orchestrator.app.exception;

import com.terrascout.orchestrator.app.security.TraceContext;
import com.terrascout.orchestrator.core.dto.ApiResponse;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * 全局异常处理器（HTTP 状态码 = 错误码 ÷ 1000）。
 *
 * <p>统一出口：{@code ApiResponse{code,message,data=null,details,traceId,timestamp}}。
 * TerraScoutException 用其自带 error 推导 HTTP 状态；校验失败映射 400003；其余映射 500007 UNKNOWN。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(TerraScoutException.class)
    public ResponseEntity<ApiResponse<Void>> handle(TerraScoutException e) {
        TerraScoutError err = e.getError();
        fillTrace(e);
        LOG.warn("[{}] business {}: {}", TraceContext.current(), err.getCode(), err.getDefaultMessage());
        return ResponseEntity.status(err.getHttpStatus()).body(ApiResponse.fail(err, e.getDetails()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        fillTrace(e);
        LOG.warn("[{}] validation: {}", TraceContext.current(), e.getMessage());
        return ResponseEntity.status(TerraScoutError.FIELD_MISSING.getHttpStatus())
                .body(ApiResponse.fail(TerraScoutError.FIELD_MISSING));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e) {
        fillTrace(e);
        LOG.error("[{}] unknown error", TraceContext.current(), e);
        return ResponseEntity.status(TerraScoutError.UNKNOWN.getHttpStatus())
                .body(ApiResponse.fail(TerraScoutError.UNKNOWN));
    }

    /** 确保异常路径也能拿到 traceId（拦截器 afterCompletion 需有 begin 才 clear）。 */
    private static void fillTrace(Exception e) {
        if (TraceContext.current() == null) {
            TraceContext.begin();
        }
    }
}
