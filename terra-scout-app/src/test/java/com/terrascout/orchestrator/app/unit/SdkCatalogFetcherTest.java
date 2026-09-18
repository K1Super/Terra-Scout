package com.terrascout.orchestrator.app.unit;

import java.io.IOException;
import java.util.List;

import com.terrascout.orchestrator.app.service.catalog.CatalogFetchResult;
import com.terrascout.orchestrator.app.service.catalog.LanguageCatalogFetcher;
import com.terrascout.orchestrator.app.service.catalog.SdkCatalogEntry;
import com.terrascout.orchestrator.app.service.catalog.SdkCatalogFetcher;
import com.terrascout.orchestrator.core.enums.ArchEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.OsTypeEnum;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 官方源目录编排器单测：逐语言聚合 + 单语言失败隔离（错误信息透传 / 空消息回退类名）。
 */
class SdkCatalogFetcherTest {

    private static SdkCatalogEntry entry(LanguageEnum language) {
        SdkCatalogEntry entry = new SdkCatalogEntry();
        entry.setLanguage(language);
        entry.setVersion("17.9.9");
        entry.setOs(OsTypeEnum.WINDOWS);
        entry.setArch(ArchEnum.AMD64);
        entry.setDownloadUrl("https://nodejs.org/dist/v17.9.9/sdk.zip");
        entry.setSha256("a".repeat(64));
        return entry;
    }

    @Test
    void aggregatesEntriesPerLanguage() throws Exception {
        LanguageCatalogFetcher java = () -> List.of(entry(LanguageEnum.JAVA));
        LanguageCatalogFetcher node = () -> List.of(entry(LanguageEnum.NODE));
        LanguageCatalogFetcher python = () -> List.of(entry(LanguageEnum.PYTHON));
        LanguageCatalogFetcher go = () -> List.of(entry(LanguageEnum.GO));
        SdkCatalogFetcher orchestrate = new SdkCatalogFetcher(java, node, python, go);

        CatalogFetchResult result = orchestrate.fetchAll();

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getEntries().keySet()).containsExactlyInAnyOrderElementsOf(List.of(LanguageEnum.values()));
        assertThat(result.getEntries().get(LanguageEnum.JAVA)).hasSize(1);
        assertThat(result.getEntries().get(LanguageEnum.NODE)).hasSize(1);
        assertThat(result.getEntries().get(LanguageEnum.PYTHON)).hasSize(1);
        assertThat(result.getEntries().get(LanguageEnum.GO)).hasSize(1);
    }

    @Test
    void isolatesFailurePerLanguage() throws Exception {
        LanguageCatalogFetcher java = () -> List.of(entry(LanguageEnum.JAVA));
        LanguageCatalogFetcher node = () -> {
            throw new IOException("node 官方源不可达");
        };
        LanguageCatalogFetcher python = () -> List.of(entry(LanguageEnum.PYTHON));
        LanguageCatalogFetcher go = () -> {
            throw new NullPointerException();
        };
        SdkCatalogFetcher orchestrate = new SdkCatalogFetcher(java, node, python, go);

        CatalogFetchResult result = orchestrate.fetchAll();

        assertThat(result.getErrors()).containsOnlyKeys(LanguageEnum.NODE, LanguageEnum.GO);
        assertThat(result.getErrors().get(LanguageEnum.NODE)).isEqualTo("node 官方源不可达");
        // 空消息回退异常类简单名
        assertThat(result.getErrors().get(LanguageEnum.GO)).isEqualTo("NullPointerException");
        // 失败语言条目以空列表占位，key 集合与 LanguageEnum 对齐
        assertThat(result.getEntries().keySet()).hasSize(LanguageEnum.values().length);
        assertThat(result.getEntries().get(LanguageEnum.NODE)).isEmpty();
        assertThat(result.getEntries().get(LanguageEnum.GO)).isEmpty();
        assertThat(result.getEntries().get(LanguageEnum.JAVA)).hasSize(1);
    }
}
