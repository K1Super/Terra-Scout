package com.terrascout.orchestrator.app.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.terrascout.orchestrator.app.util.PathSanitizer;
import com.terrascout.orchestrator.core.constant.PathConstants;

import org.springframework.stereotype.Service;

/**
 * 诊断包服务（process-management.md 5.9 / rest-schema 3.4.16）。
 *
 * <p>一键导出：日志 + 设置 + 环境信息；输出脱敏 ZIP 到 {@code {data-root}/diagnostics/}。
 * 出于安全，<b>不包含</b>含数据库密码的 {@code db.properties}（security 安全红线 7）。
 */
@Service
public class DiagnosticService {

    private static final DateTimeFormatter ZIP_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final SettingsService settingsService;

    public DiagnosticService(SettingsService settingsService) {
        this.settingsService = settingsService;
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
                putIfExists(out, PathConstants.userSettings(dataRoot), "config/user-settings.json");
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
                + "settings=" + settingsService.load();
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
