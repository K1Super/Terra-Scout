package com.terrascout.orchestrator.app.unit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.terrascout.orchestrator.app.service.DiagnosticService;
import com.terrascout.orchestrator.app.service.SettingsService;
import com.terrascout.orchestrator.core.constant.PathConstants;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 诊断包单测：日志 + 设置打包为脱敏 ZIP，不含 db.properties，AI 密钥掩码。
 */
class DiagnosticServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SettingsService settingsService = new SettingsService(objectMapper);
    private final DiagnosticService service = new DiagnosticService(settingsService, objectMapper);

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
    void createProducesZipWithLogsAndInfo() throws Exception {
        Path kernelLog = PathConstants.kernelLog(PathConstants.dataRoot());
        Files.createDirectories(kernelLog.getParent());
        Files.writeString(kernelLog, "kernel log line");
        // 预置一份含密码的 db.properties，确保被排除（安全红线约束）
        Path dbProps = PathConstants.dbProperties(PathConstants.dataRoot());
        Files.createDirectories(dbProps.getParent());
        Files.writeString(dbProps, "password=secret");

        Path zip = service.create();
        assertThat(zip).isRegularFile();
        assertThat(zip.getParent()).isEqualTo(PathConstants.diagnosticsDir(PathConstants.dataRoot()));

        try (ZipFile archive = new ZipFile(zip.toFile())) {
            ZipEntry info = archive.getEntry("info.txt");
            assertThat(info).isNotNull();
            String infoText = new String(archive.getInputStream(info).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(infoText).contains("version=0.1.0-SNAPSHOT");
            assertThat(archive.getEntry("logs/terrascout.log")).isNotNull();
            // 密码文件不应进入诊断包
            assertThat(archive.getEntry("config/db.properties")).isNull();
        }
    }

    @Test
    void aiApiKeyIsMaskedInDiagnosticZip() throws Exception {
        settingsService.save(Map.of(
                "aiProvider", "deepseek",
                "aiBaseUrl", "https://api.deepseek.com",
                "aiModel", "deepseek-chat",
                "aiApiKey", "sk-secret-0123456789"));

        Path zip = service.create();
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            ZipEntry settingsEntry = archive.getEntry("config/user-settings.json");
            assertThat(settingsEntry).isNotNull();
            String settingsText = new String(archive.getInputStream(settingsEntry).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(settingsText).doesNotContain("sk-secret-0123456789");
            assertThat(settingsText).contains("****");

            ZipEntry info = archive.getEntry("info.txt");
            String infoText = new String(archive.getInputStream(info).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(infoText).doesNotContain("sk-secret-0123456789");
        }
    }

    @Test
    void maskSecretKeepsEdgesOrHidesShortValues() {
        assertThat(DiagnosticService.maskSecret("1234")).isEqualTo("****");
        assertThat(DiagnosticService.maskSecret("sk-abcdefghijklmnop")).isEqualTo("sk-a****mnop");
        assertThat(DiagnosticService.maskSecret("")).isEmpty();
        assertThat(DiagnosticService.maskSecret(null)).isEmpty();
    }
}
