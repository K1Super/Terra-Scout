package com.terrascout.orchestrator.app.service.catalog;

import java.io.IOException;

/**
 * SDK 官方源 HTTP 客户端抽象（供各语言 fetcher 注入，单测可用假实现替换）。
 */
public interface CatalogHttpClient {

    /**
     * 以 GET 请求地址并返回 UTF-8 响应体。
     *
     * @param url 目标地址（必须通过 {@link CatalogUrlPolicy} 白名单）
     * @return 响应体文本
     * @throws IOException 网络失败、非 2xx 状态码或地址不在白名单
     */
    String get(String url) throws IOException;
}
