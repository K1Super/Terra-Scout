package com.terrascout.orchestrator.app.controller;

import java.util.ArrayList;
import java.util.List;

/**
 * 列表响应统一模型（rest-schema.md 3.6 分页：items / total / page / size）。
 */
public final class ResponseModels {

    private ResponseModels() {
    }

    /** 分页载荷。 */
    public record ApiList(List<?> items, long total, int page, int size) {
    }

    /** 快捷构造列表响应体。 */
    public static ApiList list(List<?> items, long total, int page, int size) {
        return new ApiList(new ArrayList<>(items), total, page, size);
    }
}
