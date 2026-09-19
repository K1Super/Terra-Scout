package com.terrascout.orchestrator.app.controller;

import java.util.HashMap;
import java.util.Map;

import com.terrascout.orchestrator.app.service.AuditService;
import com.terrascout.orchestrator.core.dto.ApiResponse;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 优雅关闭端点。
 *
 * <p>收到请求后记审计并异步关闭 Spring 上下文（先返回 200，让 Electron 拿到响应后再退出）。
 * 需 Token 鉴权（随 {@code /api/**} 拦截器）。
 */
@RestController
@RequestMapping("/api/v1/shutdown")
public class ShutdownController {

    private final ConfigurableApplicationContext context;
    private final AuditService auditService;

    public ShutdownController(ConfigurableApplicationContext context, AuditService auditService) {
        this.context = context;
        this.auditService = auditService;
    }

    @PostMapping
    public ApiResponse<Map<String, String>> shutdown() {
        auditService.record("system", "SYSTEM_SHUTDOWN", "system", "kernel",
                null, null, "SUCCESS");
        Thread trigger = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            context.close();
        }, "shutdown-trigger");
        trigger.setDaemon(true);
        trigger.start();
        Map<String, String> data = new HashMap<>();
        data.put("status", "SHUTTING_DOWN");
        return ApiResponse.ok(data);
    }
}
