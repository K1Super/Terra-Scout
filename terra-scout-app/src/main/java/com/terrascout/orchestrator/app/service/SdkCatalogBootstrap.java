package com.terrascout.orchestrator.app.service;

import java.nio.file.Path;
import java.util.List;

import com.terrascout.orchestrator.app.service.catalog.SdkCatalogEntry;
import com.terrascout.orchestrator.core.constant.PathConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 启动期 SDK 目录装载：把 classpath 内置 curated seed 首次落盘并装载进
 * {@code sdk_version}，保证全新安装（官方源暂不可达时）可用版本列表不空。
 *
 * <p>仅本地文件操作，不触网；任何异常仅告警，不阻断内核启动。
 * 测试环境通过 {@code terrascout.sdk-catalog.bootstrap=false} 关闭。
 */
@Component
@ConditionalOnProperty(name = "terrascout.sdk-catalog.bootstrap", havingValue = "true", matchIfMissing = true)
public class SdkCatalogBootstrap implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(SdkCatalogBootstrap.class);

    private final SdkMetadataStore metadataStore;

    public SdkCatalogBootstrap(SdkMetadataStore metadataStore) {
        this.metadataStore = metadataStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Path dataRoot = PathConstants.dataRoot();
            metadataStore.extractSeedIfAbsent(dataRoot);
            List<SdkCatalogEntry> entries =
                    metadataStore.loadFromFile(PathConstants.sdkMetadata(dataRoot));
            int loaded = metadataStore.upsertAll(entries);
            LOGGER.info("SDK 启动装载 seed 完成，入库 {} 条", loaded);
        } catch (Exception e) {
            LOGGER.warn("SDK 启动装载 seed 失败，跳过（不影响内核启动）: {}", e.getMessage());
        }
    }
}
