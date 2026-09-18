package com.terrascout.orchestrator.app.unit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.terrascout.orchestrator.app.service.SdkCatalogBootstrap;
import com.terrascout.orchestrator.app.service.SdkMetadataStore;
import com.terrascout.orchestrator.app.service.catalog.SdkCatalogEntry;
import com.terrascout.orchestrator.core.constant.PathConstants;
import com.terrascout.orchestrator.core.enums.ArchEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.OsTypeEnum;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 启动期 SDK 目录装载单测：seed 落盘 → 装载 → 入库三段调用顺序；异常不阻断启动。
 */
class SdkCatalogBootstrapTest {

    private final SdkMetadataStore store = mock(SdkMetadataStore.class);
    private final SdkCatalogBootstrap bootstrap = new SdkCatalogBootstrap(store);

    @TempDir
    Path dataRoot;

    @BeforeEach
    void setUp() {
        System.setProperty(PathConstants.DATA_DIR_PROPERTY, dataRoot.toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(PathConstants.DATA_DIR_PROPERTY);
    }

    private static SdkCatalogEntry entry() {
        SdkCatalogEntry entry = new SdkCatalogEntry();
        entry.setLanguage(LanguageEnum.JAVA);
        entry.setVersion("17.0.20.1");
        entry.setOs(OsTypeEnum.WINDOWS);
        entry.setArch(ArchEnum.AMD64);
        entry.setDownloadUrl("https://nodejs.org/dist/v17.0.20.1/jdk.zip");
        entry.setSha256("a".repeat(64));
        return entry;
    }

    @Test
    void runExtractsLoadsAndUpsertsInOrder() throws Exception {
        when(store.loadFromFile(any(Path.class))).thenReturn(List.of(entry(), entry()));
        when(store.upsertAll(anyList())).thenReturn(2);

        bootstrap.run(null);

        InOrder order = inOrder(store);
        order.verify(store).extractSeedIfAbsent(any(Path.class));
        order.verify(store).loadFromFile(any(Path.class));
        order.verify(store).upsertAll(anyList());
    }

    @Test
    void runSwallowsFailureWithoutBlockingStartup() throws Exception {
        when(store.loadFromFile(any(Path.class))).thenThrow(new IOException("seed broken"));
        assertThatCode(() -> bootstrap.run(null)).doesNotThrowAnyException();
    }
}
