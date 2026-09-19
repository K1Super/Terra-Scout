package com.terrascout.orchestrator.app.security;

/**
 * 请求级 traceId 上下文（统一响应结构含 traceId）。
 *
 * <p>每次 HTTP 请求由拦截器生成一个 UUID 写入当前线程，响应体与异常响应均带同一 traceId，
 * 便于日志关联（使用 MDC/上下文打印）。
 */
public final class TraceContext {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private TraceContext() {
    }

    /** 生成并绑定一个新的 traceId（后续调用返回同一值）。 */
    public static String begin() {
        String id = java.util.UUID.randomUUID().toString();
        TRACE_ID.set(id);
        return id;
    }

    /** 读取当前请求 traceId；无则在用后清理，避免线程复用串值。 */
    public static String current() {
        return TRACE_ID.get();
    }

    /** 请求结束清理（防线程池复用导致串号）。 */
    public static void clear() {
        TRACE_ID.remove();
    }
}
