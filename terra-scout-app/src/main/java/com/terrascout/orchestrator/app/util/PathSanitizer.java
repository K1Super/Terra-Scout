package com.terrascout.orchestrator.app.util;

/**
 * 日志 / 错误响应路径脱敏工具：路径保留最后 2 级，前面替换为 {@code ***}。
 */
public final class PathSanitizer {

    private PathSanitizer() {
    }

    /** 路径脱敏：仅保留最后 2 级目录，其余以 {@code ***} 前缀。 */
    public static String mask(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        String normalized = path.replace('\\', '/');
        String[] parts = normalized.split("/");
        int count = parts.length;
        if (count <= 2) {
            return normalized;
        }
        String last = parts[count - 1];
        String secondLast = parts[count - 2];
        return "***/" + secondLast + "/" + last;
    }
}
