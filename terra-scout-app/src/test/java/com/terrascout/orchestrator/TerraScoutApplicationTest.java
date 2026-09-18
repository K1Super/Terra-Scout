package com.terrascout.orchestrator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.terrascout.orchestrator.core.constant.PathConstants;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动入口数据根归一化单测：命令行覆盖必须同步进系统属性（PathConstants 唯一口径）。
 */
class TerraScoutApplicationTest {

    private static final String DIR_1 = "C:\\tmp\\ts-data-1";

    private static final String DIR_2 = "C:\\tmp\\ts-data-2";

    @AfterEach
    void restoreSystemProperty() {
        System.clearProperty(PathConstants.DATA_DIR_PROPERTY);
    }

    @Test
    void setsDataDirFromEqualsForm() {
        TerraScoutApplication.applyDataDirOverride(new String[] {
                "--server.port=0", "--terrascout.data-dir=" + DIR_1
        });
        assertThat(System.getProperty(PathConstants.DATA_DIR_PROPERTY)).isEqualTo(DIR_1);
        assertThat(PathConstants.dataRoot().toString()).isEqualTo(DIR_1);
    }

    @Test
    void setsDataDirFromSpaceSeparatedForm() {
        TerraScoutApplication.applyDataDirOverride(new String[] {
                "--terrascout.data-dir", DIR_2, "--terrascout.token=t"
        });
        assertThat(System.getProperty(PathConstants.DATA_DIR_PROPERTY)).isEqualTo(DIR_2);
    }

    @Test
    void keepsExistingPropertyWhenNoDataDirArg() {
        System.setProperty(PathConstants.DATA_DIR_PROPERTY, DIR_1);
        TerraScoutApplication.applyDataDirOverride(new String[] {"--server.port=0"});
        assertThat(System.getProperty(PathConstants.DATA_DIR_PROPERTY)).isEqualTo(DIR_1);
    }

    @Test
    void argWinsOverExistingProperty() {
        System.setProperty(PathConstants.DATA_DIR_PROPERTY, DIR_1);
        TerraScoutApplication.applyDataDirOverride(new String[] {
                "--terrascout.data-dir=" + DIR_2
        });
        assertThat(System.getProperty(PathConstants.DATA_DIR_PROPERTY)).isEqualTo(DIR_2);
    }

    @Test
    void ignoresBlankValue() {
        System.setProperty(PathConstants.DATA_DIR_PROPERTY, DIR_1);
        TerraScoutApplication.applyDataDirOverride(new String[] {"--terrascout.data-dir="});
        assertThat(System.getProperty(PathConstants.DATA_DIR_PROPERTY)).isEqualTo(DIR_1);
    }
}
