package com.terrascout.orchestrator.app.service.catalog;

import java.util.List;
import java.util.Map;

import com.terrascout.orchestrator.core.enums.LanguageEnum;

/**
 * 官方源抓取聚合结果：成功语言的条目 + 失败语言的错误信息（两张图以语言为键对齐）。
 */
public class CatalogFetchResult {

    private final Map<LanguageEnum, List<SdkCatalogEntry>> entries;
    private final Map<LanguageEnum, String> errors;

    public CatalogFetchResult(Map<LanguageEnum, List<SdkCatalogEntry>> entries,
                              Map<LanguageEnum, String> errors) {
        this.entries = entries;
        this.errors = errors;
    }

    /** 各语言成功抓取的条目（失败语言在 entries 中为空列表）。 */
    public Map<LanguageEnum, List<SdkCatalogEntry>> getEntries() {
        return entries;
    }

    /** 各语言失败原因（仅失败语言有键）。 */
    public Map<LanguageEnum, String> getErrors() {
        return errors;
    }
}
