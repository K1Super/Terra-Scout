package com.terrascout.orchestrator.app.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.terrascout.orchestrator.app.util.PathSanitizer;
import com.terrascout.orchestrator.core.constant.PathConstants;

import org.springframework.stereotype.Service;

/**
 * 诊断包服务。
 *
 * <p>一键导出：日志 + 设置 + 环境信息；输出脱敏 ZIP 到 {@code {data-root}/diagnostics/}。
 * 出于安全，<b>不包含</b>含数据库密码的 {@code db.properties}（安全红线约束）；
 * AI 接入的 {@code aiApiKey} 打包前掩码，不落诊断明文。
 */
@Service
public class DiagnosticService {

    private static final DateTimeFormatter ZIP_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;

    public DiagnosticService(SettingsService settingsService, ObjectMapper objectMapper) {
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
    }

    /** 生成诊断包，返回 ZIP 路径。 */
    public Path create() {
        Path dataRoot = PathConstants.dataRoot();
        String ts = LocalDateTime.now().format(ZIP_TS);
        Path zipPath = PathConstants.diagnosticsDir(dataRoot)
                .resolve("diagnostic-" + ts + ".zip");
        try {
            Files.createDirectories(zipPath.getParent());
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zipPath),
                    StandardCharsets.UTF_8)) {
                putText(out, "info.txt", buildInfo());
                putIfExists(out, PathConstants.kernelLog(dataRoot), "logs/terrascout.log");
                putIfExists(out, PathConstants.logsDir(dataRoot).resolve(PathConstants.FILE_LOG_ELECTRON),
                        "logs/electron.log");
                putSettingsMasked(out, "config/user-settings.json");
                putIfExists(out, PathConstants.sdkMetadata(dataRoot), "config/sdk-metadata.json");
            }
        } catch (IOException e) {
            throw new IllegalStateException("诊断包生成失败", e);
        }
        return zipPath;
    }

    private String buildInfo() {
        return "version=0.1.0-SNAPSHOT" + System.lineSeparator()
                + "java=" + System.getProperty("java.version") + System.lineSeparator()
                + "os=" + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + System.lineSeparator()
                + "dataDir=" + PathSanitizer.mask(PathConstants.dataRoot().toString())
                + System.lineSeparator()
                + "settings=" + maskedSettings();
    }

    /** 写设置快照，AI 密钥掩码后落 zip。 */
    private void putSettingsMasked(ZipOutputStream out, String entryName) throws IOException {
        out.putNextEntry(new ZipEntry(entryName));
        out.write(objectMapper.writeValueAsBytes(maskedSettings()));
        out.closeEntry();
    }

    private Map<String, Object> maskedSettings() {
        Map<String, Object> settings = new LinkedHashMap<>(settingsService.load());
        Object apiKey = settings.get("aiApiKey");
        if (apiKey != null) {
            settings.put("aiApiKey", maskSecret(apiKey.toString()));
        }
        return settings;
    }

    /** 密钥掩码：保留首尾 4 位便于辨识，中间掩为星号；过短整体掩码。 */
    public static String maskSecret(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private static void putText(ZipOutputStream out, String name, String content) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static void putIfExists(ZipOutputStream out, Path source, String entryName) throws IOException {
        if (Files.isRegularFile(source)) {
            out.putNextEntry(new ZipEntry(entryName));
            Files.copy(source, out);
            out.closeEntry();
        }
    }
}
