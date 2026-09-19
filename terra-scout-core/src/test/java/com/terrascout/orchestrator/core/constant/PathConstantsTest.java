package com.terrascout.orchestrator.core.constant;

import java.nio.file.Path;
import java.nio.file.Paths;

import com.terrascout.orchestrator.core.enums.LanguageEnum;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PathConstants 断言（统一路径约定表）。
 */
class PathConstantsTest {

    private static final Path ROOT = Paths.get("D:", "data-root");

    @AfterEach
    void clearOverride() {
        System.clearProperty(PathConstants.DATA_DIR_PROPERTY);
    }

    @Test
    @DisplayName("默认数据根 = user.home/.terrascout")
    void defaultDataRootIsUserProfileDotTerrascout() {
        assertThat(PathConstants.defaultDataRoot())
                .isEqualTo(Paths.get(System.getProperty("user.home"), ".terrascout"));
    }

    @Test
    @DisplayName("dataRoot() 未覆盖时取默认，被系统属性覆盖时取覆盖值")
    void dataRootRespectsOverride() {
        assertThat(PathConstants.dataRoot()).isEqualTo(PathConstants.defaultDataRoot());

        System.setProperty(PathConstants.DATA_DIR_PROPERTY, "D:\\custom-root");
        assertThat(PathConstants.dataRoot()).isEqualTo(Paths.get("D:\\custom-root"));

        System.setProperty(PathConstants.DATA_DIR_PROPERTY, "  ");
        assertThat(PathConstants.dataRoot()).isEqualTo(PathConstants.defaultDataRoot());
    }

    @Test
    @DisplayName("数据根内路径组装与路径表一致")
    void dataRootPathsMatchSpec() {
        assertThat(PathConstants.databaseFile(ROOT)).isEqualTo(ROOT.resolve("db").resolve("terrascout.mv.db"));
        assertThat(PathConstants.dbProperties(ROOT)).isEqualTo(ROOT.resolve("config").resolve("db.properties"));
        assertThat(PathConstants.userSettings(ROOT)).isEqualTo(ROOT.resolve("config").resolve("user-settings.json"));
        assertThat(PathConstants.sdkMetadata(ROOT)).isEqualTo(ROOT.resolve("config").resolve("sdk-metadata.json"));
        assertThat(PathConstants.logsDir(ROOT)).isEqualTo(ROOT.resolve("logs"));
        assertThat(PathConstants.kernelLog(ROOT)).isEqualTo(ROOT.resolve("logs").resolve("terrascout.log"));
        assertThat(PathConstants.sdkRepository(ROOT)).isEqualTo(ROOT.resolve("sdks"));
        assertThat(PathConstants.backupDir(ROOT)).isEqualTo(ROOT.resolve("db").resolve("backup"));
        assertThat(PathConstants.diagnosticsDir(ROOT)).isEqualTo(ROOT.resolve("diagnostics"));
    }

    @Test
    @DisplayName("SDK 仓库布局 = sdks/{language小写}/{version}")
    void sdkHomeMatchesD004Layout() {
        assertThat(PathConstants.sdkHome(ROOT, LanguageEnum.JAVA, "17.0.9"))
                .isEqualTo(ROOT.resolve("sdks").resolve("java").resolve("17.0.9"));
        assertThat(PathConstants.sdkHome(ROOT, LanguageEnum.NODE, "20.11.1"))
                .isEqualTo(ROOT.resolve("sdks").resolve("node").resolve("20.11.1"));
    }

    @Test
    @DisplayName("启动备份文件名 = terrascout-{timestamp}.mv.db")
    void backupFileNaming() {
        assertThat(PathConstants.backupFile(ROOT, "20260916120000"))
                .isEqualTo(ROOT.resolve("db").resolve("backup").resolve("terrascout-20260916120000.mv.db"));
    }

    @Test
    @DisplayName("项目隔离域布局（.devenv 只含 m2/npm-cache/env.ps1）")
    void isolationLayoutMatchesD004() {
        Path projectRoot = Paths.get("D:", "workspace", "demo");
        assertThat(PathConstants.isolationRoot(projectRoot)).isEqualTo(projectRoot.resolve(".devenv"));
        assertThat(PathConstants.isolationM2(projectRoot)).isEqualTo(projectRoot.resolve(".devenv").resolve("m2"));
        assertThat(PathConstants.isolationNpmCache(projectRoot))
                .isEqualTo(projectRoot.resolve(".devenv").resolve("npm-cache"));
        assertThat(PathConstants.isolationEnvScript(projectRoot))
                .isEqualTo(projectRoot.resolve(".devenv").resolve("env.ps1"));
    }

    @Test
    @DisplayName("入参防御：null 根路径 / 空版本 / 空时间戳快速失败")
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> PathConstants.databaseFile(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PathConstants.sdkHome(ROOT, null, "1.0"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PathConstants.sdkHome(ROOT, LanguageEnum.JAVA, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PathConstants.sdkHome(ROOT, LanguageEnum.JAVA, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathConstants.backupFile(ROOT, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
