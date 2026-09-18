package com.terrascout.orchestrator.app.unit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.terrascout.orchestrator.app.service.SettingsService;
import com.terrascout.orchestrator.core.constant.PathConstants;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设置服务单测（D-013）：默认打底 / 白名单过滤 / 持久化回读 / 损坏回退。
 *
 * <p>非 Spring 上下文，用真实 ObjectMapper，数据根重定向到临时目录。
 */
class SettingsServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SettingsService service = new SettingsService(objectMapper);

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
    void loadReturnsDefaultsWhenNoFile() {
        Map<String, Object> loaded = service.load();
        assertThat(loaded).containsKey("download");
        assertThat((String) ((Map<?, ?>) loaded.get("download")).get("mirror")).contains("huaweicloud");
        assertThat((String) loaded.get("logLevel")).isEqualTo("INFO");
    }

    @Test
    void saveThenLoadRoundTrip() throws Exception {
        Map<String, Object> saved = service.save(Map.of("logLevel", "DEBUG", "aiEnabled", true));
        assertThat((String) saved.get("logLevel")).isEqualTo("DEBUG");
        assertThat((Boolean) saved.get("aiEnabled")).isEqualTo(true);

        Map<String, Object> reloaded = service.load();
        assertThat((String) reloaded.get("logLevel")).isEqualTo("DEBUG");
        assertThat((Boolean) reloaded.get("aiEnabled")).isEqualTo(true);
        // user-settings.json 已持久化
        assertThat(Files.isRegularFile(
                PathConstants.userSettings(PathConstants.dataRoot()))).isTrue();
    }

    @Test
    void unknownKeysAreFilteredFromSave() {
        Map<String, Object> saved = service.save(Map.of("logLevel", "WARN", "evilKey", "x"));
        assertThat(saved).doesNotContainKey("evilKey");
        assertThat((String) saved.get("logLevel")).isEqualTo("WARN");
    }

    @Test
    void corruptedFileFallsBackToDefaults() throws Exception {
        Path file = PathConstants.userSettings(PathConstants.dataRoot());
        Files.createDirectories(file.getParent());
        Files.write(file, "{{{ not-json".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> loaded = service.load();
        assertThat((String) loaded.get("logLevel")).isEqualTo("INFO");
    }
}
