package com.terrascout.orchestrator.app.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.terrascout.orchestrator.app.service.AuditService;
import com.terrascout.orchestrator.core.domain.AuditLog;
import com.terrascout.orchestrator.core.dto.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计日志端点：查询 + hash 链校验结果。
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String bizId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<AuditLog> all = auditService.findAllOrderedBySeq().stream()
                .filter(a -> bizId == null || bizId.isBlank() || bizId.equals(a.getBizId()))
                .filter(a -> action == null || action.isBlank() || action.equals(a.getAction()))
                .collect(Collectors.toList());
        boolean chainValid = auditService.chainValid(all);

        Map<String, Object> data = new HashMap<>();
        data.put("chainValid", chainValid);
        data.put("items", all.stream().map(this::toItem).collect(Collectors.toList()));
        data.put("total", all.size());
        data.put("page", page);
        data.put("size", size);
        return ApiResponse.ok(data);
    }

    private Map<String, Object> toItem(AuditLog entry) {
        Map<String, Object> item = new HashMap<>();
        item.put("seq", entry.getSeq());
        item.put("bizId", entry.getBizId());
        item.put("action", entry.getAction());
        item.put("targetType", entry.getTargetType());
        item.put("targetId", entry.getTargetId());
        item.put("result", entry.getResult());
        item.put("createdAt", entry.getCreatedAt());
        return item;
    }
}
