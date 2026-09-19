package com.terrascout.orchestrator.core.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Terra Scout 统一业务异常。
 *
 * <p>使用约束：
 * <ul>
 *   <li>业务校验失败抛出本异常，携带对应 {@link TerraScoutError}；</li>
 *   <li>系统异常先包装：{@code new TerraScoutException(TerraScoutError.UNKNOWN, e.getMessage(), e)}；</li>
 *   <li>全局异常处理器按 {@link #getHttpStatus()} 渲染 HTTP 状态（恒等于 code 前 3 位）。</li>
 * </ul>
 */
public class TerraScoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final TerraScoutError error;

    /** 结构化明细（ErrorResponse.details），不参与堆栈序列化。 */
    private final transient Map<String, Object> details;

    public TerraScoutException(TerraScoutError error) {
        super(error.getDefaultMessage());
        this.error = error;
        this.details = null;
    }

    public TerraScoutException(TerraScoutError error, String message) {
        super(message);
        this.error = error;
        this.details = null;
    }

    public TerraScoutException(TerraScoutError error, Map<String, Object> details) {
        super(error.getDefaultMessage());
        this.error = error;
        this.details = details == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public TerraScoutException(TerraScoutError error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
        this.details = null;
    }

    public TerraScoutError getError() {
        return error;
    }

    /** HTTP 状态码恒等于错误码前 3 位（强约束）。 */
    public int getHttpStatus() {
        return error.getHttpStatus();
    }

    /** 只读明细；无明细时返回 null。 */
    public Map<String, Object> getDetails() {
        return details;
    }
}
