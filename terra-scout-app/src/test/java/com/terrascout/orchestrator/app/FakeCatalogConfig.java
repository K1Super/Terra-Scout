package com.terrascout.orchestrator.app;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.terrascout.orchestrator.app.service.catalog.CatalogFetchResult;
import com.terrascout.orchestrator.app.service.catalog.SdkCatalogEntry;
import com.terrascout.orchestrator.app.service.catalog.SdkOfficialCatalogFetcher;
import com.terrascout.orchestrator.core.enums.ArchEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.OsTypeEnum;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 假官方源目录编排器（测试专用）：确定性夹具替代真实网络抓取（测试隔离）。
 *
 * <p>顶层 {@link TestConfiguration}：jar 内无法在 {@code @SpringBootTest(classes=...)}
 * 注解中引用测试类自身的嵌套类（javac 在类头归因阶段不可见），故独立成顶层类并显式注册。
 */
@TestConfiguration
public class FakeCatalogConfig {

    @Bean
    @Primary
    SdkOfficialCatalogFetcher fakeCatalogFetcher() {
        return () -> {
            Map<LanguageEnum, List<SdkCatalogEntry>> entries = new EnumMap<>(LanguageEnum.class);
            for (LanguageEnum language : LanguageEnum.values()) {
                entries.put(language, List.of(fakeEntry(language)));
            }
            return new CatalogFetchResult(entries, Map.of());
        };
    }

    private static SdkCatalogEntry fakeEntry(LanguageEnum language) {
        SdkCatalogEntry entry = new SdkCatalogEntry();
        entry.setLanguage(language);
        entry.setVersion("17.9.9");
        entry.setOs(OsTypeEnum.WINDOWS);
        entry.setArch(ArchEnum.AMD64);
        entry.setDownloadUrl("https://nodejs.org/dist/v17.9.9/sdk.zip");
        entry.setSha256("ab".repeat(32));
        entry.setLts(true);
        return entry;
    }
}
