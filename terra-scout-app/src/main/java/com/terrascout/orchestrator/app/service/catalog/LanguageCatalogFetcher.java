package com.terrascout.orchestrator.app.service.catalog;

import java.util.List;

/**
 * 单语言官方源目录抓取器。
 *
 * <p>实现类负责：请求官方源 → 解析响应 → 产出本机可安装（WINDOWS/AMD64、
 * zip 归档、带真实 sha256）的版本条目列表；抓取失败抛异常由编排层按语言隔离。
 */
public interface LanguageCatalogFetcher {

    /**
     * 抓取该语言的可用版本目录。
     *
     * @return 版本条目列表（可能为空；语义为「官方源当前无可用版本」）
     * @throws Exception 官方源不可达、响应不可解析等抓取失败
     */
    List<SdkCatalogEntry> fetch() throws Exception;
}
