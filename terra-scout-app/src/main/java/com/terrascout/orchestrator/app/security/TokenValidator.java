package com.terrascout.orchestrator.app.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Token 校验器（security.md 6.2，D-001 / D-016）。
 *
 * <p>常量时间比较防时序攻击；免鉴权白名单当前仅 {@code GET /api/v1/health}。
 * Token 由 Electron 经命令行传入进程（{@code terrascout.token}），进程生命周期内不变、不落盘。
 */
@Component
public class TokenValidator {

    /** 免鉴权端点（唯一）：GET /api/v1/health。 */
    public static final String WHITELIST_PATH = "/api/v1/health";

    /** 鉴权请求头。 */
    public static final String HEADER_TOKEN = "X-TerraScout-Token";

    /** 期望 Token（命令行为空时允许健康检查被免鉴权访问）。 */
    private final String expectedToken;

    public TokenValidator(@Value("${terrascout.token:}") String expectedToken) {
        this.expectedToken = expectedToken == null ? "" : expectedToken;
    }

    /** 判断请求路径是否在免鉴权白名单内。 */
    public boolean isWhitelisted(String httpMethod, String servletPath) {
        return "GET".equalsIgnoreCase(httpMethod)
                && WHITELIST_PATH.equals(servletPath);
    }

    /** 常量时间比较请求 Token 与期望 Token。 */
    public boolean isTokenValid(String provided) {
        String actual = provided == null ? "" : provided;
        byte[] a = expectedToken.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int result = a.length ^ b.length;
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int ai = i < a.length ? a[i] & 0xFF : 0;
            int bi = i < b.length ? b[i] & 0xFF : 0;
            result |= ai ^ bi;
        }
        return result == 0 && expectedToken.length() > 0;
    }
}
