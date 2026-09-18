package com.terrascout.orchestrator.app.service.catalog;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.terrascout.orchestrator.core.enums.LanguageEnum;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 官方源目录编排器：逐语言抓取并隔离异常（单语言失败不影响其余语言）。
 */
@Component
public class SdkCatalogFetcher implements SdkOfficialCatalogFetcher {

    private final Map<LanguageEnum, LanguageCatalogFetcher> fetchers;

    public SdkCatalogFetcher(@Qualifier("adoptiumJavaFetcher") LanguageCatalogFetcher javaFetcher,
                             @Qualifier("nodeJsFetcher") LanguageCatalogFetcher nodeFetcher,
                             @Qualifier("pythonFetcher") LanguageCatalogFetcher pythonFetcher,
                             @Qualifier("goFetcher") LanguageCatalogFetcher goFetcher) {
        this.fetchers = new EnumMap<>(LanguageEnum.class);
        this.fetchers.put(LanguageEnum.JAVA, javaFetcher);
        this.fetchers.put(LanguageEnum.NODE, nodeFetcher);
        this.fetchers.put(LanguageEnum.PYTHON, pythonFetcher);
        this.fetchers.put(LanguageEnum.GO, goFetcher);
    }

    @Override
    public CatalogFetchResult fetchAll() {
        Map<LanguageEnum, List<SdkCatalogEntry>> entries = new EnumMap<>(LanguageEnum.class);
        Map<LanguageEnum, String> errors = new EnumMap<>(LanguageEnum.class);
        for (LanguageEnum language : LanguageEnum.values()) {
            try {
                entries.put(language, fetchers.get(language).fetch());
            } catch (Exception e) {
                // 单语言失败仅记错误，条目以空列表占位，保证 key 集合与 LanguageEnum 对齐
                String message = e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage();
                errors.put(language, message);
                entries.put(language, List.of());
            }
        }
        return new CatalogFetchResult(entries, errors);
    }
}
