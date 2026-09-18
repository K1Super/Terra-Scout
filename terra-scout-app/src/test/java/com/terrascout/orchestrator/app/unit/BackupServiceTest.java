package com.terrascout.orchestrator.app.unit;

import java.nio.file.Files;
import java.nio.file.Path;

import com.terrascout.orchestrator.app.service.BackupService;
import com.terrascout.orchestrator.core.constant.PathConstants;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据库备份单测（process-management 5.9 / D-003）：文件库拷贝生成时间戳备份。
 */
class BackupServiceTest {

    private final BackupService service = new BackupService();

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

    @Test
    void backupCopiesDatabaseFileToDatedName() throws Exception {
        Path source = PathConstants.databaseFile(PathConstants.dataRoot());
        Files.createDirectories(source.getParent());
        Files.writeString(source, "fake-h2-content");

        Path backup = service.backup();
        assertThat(backup).isRegularFile();
        assertThat(backup.getFileName().toString()).startsWith("terrascout-");
        assertThat(backup.getFileName().toString()).endsWith(".mv.db");
        assertThat(backup.getParent()).isEqualTo(PathConstants.backupDir(PathConstants.dataRoot()));
    }

    @Test
    void backupWithoutDatabaseFileThrows() {
        assertThatThrownBy(() -> service.backup())
                .isInstanceOf(IllegalStateException.class);
    }
}
