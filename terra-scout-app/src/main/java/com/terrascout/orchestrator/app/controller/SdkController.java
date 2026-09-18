package com.terrascout.orchestrator.app.controller;

import java.util.List;
import java.util.Map;

import com.terrascout.orchestrator.app.service.SdkService;
import com.terrascout.orchestrator.core.dto.ApiResponse;
import com.terrascout.orchestrator.core.enums.ArchEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.OsTypeEnum;
import com.terrascout.orchestrator.core.enums.ScopeEnum;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SDK 端点（rest-schema.md 3.4.10-3.4.12、3.4.18）：版本列表 / 异步安装 / 安装进度 / 卸载 / 元数据重载。
 */
@RestController
@RequestMapping("/api/v1/sdk")
public class SdkController {

    private final SdkService sdkService;

    public SdkController(SdkService sdkService) {
        this.sdkService = sdkService;
    }

    /** 版本列表（3.4.10，可按语言/OS/架构过滤，版本号升序返回）。 */
    @GetMapping("/list")
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) LanguageEnum language,
            @RequestParam(required = false) OsTypeEnum os,
            @RequestParam(required = false) ArchEnum arch) {
        List<Map<String, Object>> items = sdkService.list(language, os, arch);
        return ApiResponse.ok(Map.of("items", items, "total", items.size()));
    }

    /** 安装（3.4.11）：异步任务，立即返回 jobId；已装版本直接返回 SUCCESS 记录。 */
    @PostMapping("/install")
    public ApiResponse<Map<String, Object>> install(@RequestBody SdkInstallRequest body) {
        return ApiResponse.ok(sdkService.install(body.getLanguage(), body.getVersion(),
                body.getScope(), body.getProjectId(), body.getInstallDir()));
    }

    /** 安装进度查询（3.4.18）：按安装任务 jobId 轮询快照。 */
    @GetMapping("/install/{jobId}")
    public ApiResponse<Map<String, Object>> installProgress(@PathVariable String jobId) {
        return ApiResponse.ok(sdkService.installProgress(jobId));
    }

    /** 取消安装任务（3.4.18）：置取消信号；工作线程停止下载/解压并清理残留后收敛 CANCELLED。 */
    @PostMapping("/install/{jobId}/cancel")
    public ApiResponse<Map<String, Object>> cancelInstall(@PathVariable String jobId) {
        return ApiResponse.ok(sdkService.cancelInstall(jobId));
    }

    /** 卸载（3.4.11）。 */
    @PostMapping("/uninstall")
    public ApiResponse<Map<String, Object>> uninstall(@RequestBody SdkUninstallRequest body) {
        return ApiResponse.ok(sdkService.uninstall(body.getRecordId()));
    }

    /** 元数据重载（3.4.12，D-009）。 */
    @PostMapping("/metadata/reload")
    public ApiResponse<Map<String, Object>> reloadMetadata() {
        return ApiResponse.ok(sdkService.reloadMetadata());
    }

    /** 安装请求体（3.4.11）。 */
    public static class SdkInstallRequest {
        private LanguageEnum language;
        private String version;
        private ScopeEnum scope;
        private String projectId;
        /** 可选（R45）：自定义安装根目录（绝对路径）；缺省回落标准仓库 {data-root}\sdks。 */
        private String installDir;

        public LanguageEnum getLanguage() {
            return language;
        }

        public void setLanguage(LanguageEnum language) {
            this.language = language;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public ScopeEnum getScope() {
            return scope;
        }

        public void setScope(ScopeEnum scope) {
            this.scope = scope;
        }

        public String getProjectId() {
            return projectId;
        }

        public void setProjectId(String projectId) {
            this.projectId = projectId;
        }

        public String getInstallDir() {
            return installDir;
        }

        public void setInstallDir(String installDir) {
            this.installDir = installDir;
        }
    }

    /** 卸载请求体（3.4.11）。 */
    public static class SdkUninstallRequest {
        private String recordId;

        public String getRecordId() {
            return recordId;
        }

        public void setRecordId(String recordId) {
            this.recordId = recordId;
        }
    }
}
