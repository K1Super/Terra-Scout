package com.terrascout.orchestrator.core.dto;

/**
 * 项目导入分析请求（rest-schema.md 3.4.1；openapi AnalyzeRequest）。
 *
 * <p>path 必须为 Windows 盘符绝对路径（pattern {@code ^[A-Za-z]:\\.*}，1-4096 字符），
 * 非法值在 app 层校验并返回 400001；idempotencyKey 为 UUID v4，格式非法返回 400004。
 */
public class AnalyzeRequest {

    /** 项目根目录绝对路径（Windows 盘符格式）。 */
    private String path;

    /** 幂等键（UUID v4，可选）。 */
    private String idempotencyKey;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
