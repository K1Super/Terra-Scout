package com.terrascout.orchestrator.app.repository;

import com.terrascout.orchestrator.core.domain.AuditLog;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 审计日志仓储（表 audit_log；biz_id 普通索引，hash 链在 AuditService 维护，D-015）。
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
