package com.terrascout.orchestrator.app.controller;

import java.util.Map;

import com.terrascout.orchestrator.core.dto.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查（rest-schema.md 3.4.17）：唯一免鉴权端点，返回 HTTP 200 + 200000 + status "UP"。
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.ok(Map.of("status", "UP"));
    }
}
