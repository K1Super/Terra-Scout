package com.terrascout.orchestrator.core.dto;

import java.util.Map;

import com.terrascout.orchestrator.core.error.TerraScoutError;

/**
 * 统一响应结构（ApiResponse）。
 *
 * <p>code = 200000 表示成功；错误时 code 为 6 位错误码，
 * HTTP 状态码由 app 层按 {@link TerraScoutError#getHttpStatus()} 渲染。
 * traceId 由 app 层 Web 过滤器注入。
 */
public class ApiResponse<T> {

    /** 6 位码：200000 成功；其余为错误码（前 3 位 = HTTP 状态码）。 */
    private int code;

    private String message;

    private T data;

    private String traceId;

    private long timestamp;

    /** 结构化明细（ErrorResponse.details），无则为 null。 */
    private Map<String, Object> details;

    /** 成功响应工厂（message 固定 "success"）。 */
    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = TerraScoutError.SUCCESS.getCode();
        response.message = TerraScoutError.SUCCESS.getDefaultMessage();
        response.data = data;
        response.timestamp = System.currentTimeMillis();
        return response;
    }

    /** 失败响应工厂（不含明细）。 */
    public static <T> ApiResponse<T> fail(TerraScoutError error) {
        return fail(error, null);
    }

    /** 失败响应工厂（含结构化明细）。 */
    public static <T> ApiResponse<T> fail(TerraScoutError error, Map<String, Object> details) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = error.getCode();
        response.message = error.getDefaultMessage();
        response.details = details;
        response.timestamp = System.currentTimeMillis();
        return response;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details;
    }
}
