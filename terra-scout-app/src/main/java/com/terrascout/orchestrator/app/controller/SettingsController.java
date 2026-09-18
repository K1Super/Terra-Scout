package com.terrascout.orchestrator.app.controller;

import java.util.Map;

import com.terrascout.orchestrator.app.service.SettingsService;
import com.terrascout.orchestrator.core.dto.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设置端点（rest-schema.md 3.4.15 / master-plan D-013）：读取 / 更新 user-settings.json。
 */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
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
}
