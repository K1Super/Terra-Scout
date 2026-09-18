package com.terrascout.orchestrator.app.service.catalog;

/**
 * SDK 官方源目录聚合抓取入口（服务层依赖本接口，集成测试可用假实现替换）。
 */
public interface SdkOfficialCatalogFetcher {

    /**
     * 抓取全部语言官方源目录；单语言失败不拖累其他语言，错误按语言记录在结果中。
     *
     * @return 分语言条目与错误信息
     */
    CatalogFetchResult fetchAll();
}
