package com.terrascout.orchestrator.app.unit;

import java.nio.file.Files;
import java.nio.file.Path;

import com.terrascout.orchestrator.core.constant.PathConstants;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动引导单测（ddl-migration.md 2.1 / 2.5 / D-006）：
 * 数据根目录树创建 + 数据库密码持久化（首启生成、复用）。
 */
class StartupGuardTest {

    private static final String PROP = PathConstants.DATA_DIR_PROPERTY;
    private String previousDir;

    @TempDir
    Path tempDir;

    @AfterEach
    void restoreDataDir() {
        if (previousDir != null) {
            System.setProperty(PROP, previousDir);
        } else {
            System.clearProperty(PROP);
        }
    }

    private void pointDataDirTo(Path dir) {
        previousDir = System.getProperty(PROP);
        System.setProperty(PROP, dir.toString());
    }

    @Test
    void ensureDatabasePasswordCreatesPropertyAndReuses() throws Exception {
        pointDataDirTo(tempDir);
        String first = com.terrascout.orchestrator.app.startup.StartupGuard.ensureDatabasePassword();

        Path propFile = PathConstants.dbProperties(PathConstants.dataRoot());
        assertThat(Files.exists(propFile)).isTrue();

        // 二次启动复用同一密码
        String second = com.terrascout.orchestrator.app.startup.StartupGuard.ensureDatabasePassword();
        assertThat(second).isEqualTo(first);
        assertThat(first).isNotBlank();
    }

    @Test
    void ensureDatabasePasswordGeneratesUniqueAcrossDirs() {
        Path d1 = tempDir.resolve("d1");
        Path d2 = tempDir.resolve("d2");
        pointDataDirTo(d1);
        String p1 = com.terrascout.orchestrator.app.startup.StartupGuard.ensureDatabasePassword();
        pointDataDirTo(d2);
        String p2 = com.terrascout.orchestrator.app.startup.StartupGuard.ensureDatabasePassword();
        assertThat(p1).isNotEqualTo(p2);
    }

    @Test
    void prepareDataRootCreatesDirectoryTree() {
        pointDataDirTo(tempDir);
        com.terrascout.orchestrator.app.startup.StartupGuard.prepareDataRoot();
        assertThat(Files.isDirectory(PathConstants.dataRoot())).isTrue();
        assertThat(Files.isDirectory(PathConstants.dataRoot().resolve(PathConstants.DIR_DB)
                .resolve(PathConstants.DIR_BACKUP))).isTrue();
        assertThat(Files.isDirectory(PathConstants.dataRoot().resolve(PathConstants.DIR_CONFIG))).isTrue();
        assertThat(Files.isDirectory(PathConstants.dataRoot().resolve(PathConstants.DIR_SDKS))).isTrue();
        assertThat(Files.isDirectory(PathConstants.dataRoot().resolve(PathConstants.DIR_LOGS))).isTrue();
    }
}
