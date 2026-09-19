package com.terrascout.orchestrator.app.controller;

import java.util.Map;

import com.terrascout.orchestrator.app.service.AiConnectivityTester;
import com.terrascout.orchestrator.app.service.AiConnectivityTester.AiTestResult;
import com.terrascout.orchestrator.app.service.SettingsService;
import com.terrascout.orchestrator.app.service.SettingsService.AiTemplate;
import com.terrascout.orchestrator.core.dto.ApiResponse;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设置端点：读取 / 更新 user-settings.json，以及 AI 接入连通性测试。
 */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private final SettingsService settingsService;
    private final AiConnectivityTester aiConnectivityTester;

    public SettingsController(SettingsService settingsService, AiConnectivityTester aiConnectivityTester) {
        this.settingsService = settingsService;
        this.aiConnectivityTester = aiConnectivityTester;
    }

    /** 读取设置（内置默认打底）。 */
    @GetMapping
    public ApiResponse<Map<String, Object>> get() {
        return ApiResponse.ok(settingsService.load());
    }

    /** 更新设置（白名单键，持久化）。 */
    @PutMapping
    public ApiResponse<Map<String, Object>> update(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(settingsService.save(body));
    }

    /**
     * AI 接入连通性测试：对提交的配置（不落盘）发起一次最小真实调用。
     * 探测失败属预期结果之一，统一 200 + ok=false + message 表达，不抛业务错误。
     */
    @PostMapping("/ai/test")
    public ApiResponse<AiTestResult> aiTest(@RequestBody Map<String, Object> body) {
        String provider = str(body.get("aiProvider"));
        String baseUrl = str(body.get("aiBaseUrl"));
        String apiKey = str(body.get("aiApiKey"));
        String model = str(body.get("aiModel"));

        AiTemplate template = provider == null ? null : SettingsService.AI_TEMPLATES.get(provider);
        if (template == null) {
            throw new TerraScoutException(TerraScoutError.AI_CONFIG_INVALID, "不支持的 AI 供应商");
        }
        if (apiKey.isEmpty()) {
            throw new TerraScoutException(TerraScoutError.AI_CONFIG_INVALID, "API Key 不能为空");
        }
        if (baseUrl.isEmpty()) {
            baseUrl = template.baseUrl();
        }
        if (model.isEmpty()) {
            model = template.model();
        }
        return ApiResponse.ok(
                aiConnectivityTester.test(baseUrl, apiKey, model));
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
