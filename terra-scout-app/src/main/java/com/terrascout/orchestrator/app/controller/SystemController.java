package com.terrascout.orchestrator.app.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.terrascout.orchestrator.app.service.BackupService;
import com.terrascout.orchestrator.app.service.DiagnosticService;
import com.terrascout.orchestrator.core.constant.PathConstants;
import com.terrascout.orchestrator.core.dto.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统端点（rest-schema.md 3.4.16）：系统信息 / 备份 / 诊断包。
 *
 * <p>本机桌面 UI 消费的路径字段返回<b>完整真实路径</b>（与 SDK 列表中
 * installedPath 的处理一致；展示对象是本机用户本人，无信息泄露面）。
 * {@link com.terrascout.orchestrator.app.util.PathSanitizer} 仍专用于
 * 日志落盘、错误响应与诊断包<b>内容</b>的脱敏。
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final BackupService backupService;
    private final DiagnosticService diagnosticService;

    public SystemController(BackupService backupService, DiagnosticService diagnosticService) {
        this.backupService = backupService;
        this.diagnosticService = diagnosticService;
    }

    @GetMapping("/info")
    public ApiResponse<Map<String, Object>> info() {
        Map<String, Object> data = new HashMap<>();
        data.put("version", "0.1.0-SNAPSHOT");
        data.put("javaVersion", System.getProperty("java.version"));
        data.put("dataDir", PathConstants.dataRoot().toString());
        data.put("sdkRepoDir", PathConstants.sdkRepository(PathConstants.dataRoot()).toString());
        data.put("dbSizeBytes", dbSizeBytes());
        return ApiResponse.ok(data);
    }

    /** 系统健康状态（供前端探活）。 */
    @GetMapping("/health-status")
    public ApiResponse<Map<String, String>> kernelHealth() {
        Map<String, String> data = new HashMap<>();
        data.put("status", "UP");
        return ApiResponse.ok(data);
    }

    /** 立即备份数据库（3.4.16）。 */
    @PostMapping("/backup")
    public ApiResponse<Map<String, Object>> backup() {
        Path backupFile = backupService.backup();
        Map<String, Object> data = new HashMap<>();
        data.put("backupFile", backupFile.toString());
        return ApiResponse.ok(data);
    }

    /** 导出诊断包（3.4.16；ZIP 内容由 DiagnosticService 脱敏，返回完整落盘路径）。 */
    @GetMapping("/diagnostic")
    public ApiResponse<Map<String, Object>> diagnostic() {
        Path zipPath = diagnosticService.create();
        Map<String, Object> data = new HashMap<>();
        data.put("zipPath", zipPath.toString());
        return ApiResponse.ok(data);
    }

    /** H2 文件库字节数；文件不存在返回 0。 */
    private long dbSizeBytes() {
        Path dbFile = PathConstants.databaseFile(PathConstants.dataRoot());
        try {
            return Files.exists(dbFile) ? Files.size(dbFile) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }
}
