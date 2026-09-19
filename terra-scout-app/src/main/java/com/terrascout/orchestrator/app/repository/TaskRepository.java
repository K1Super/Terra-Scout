package com.terrascout.orchestrator.app.repository;

import java.util.List;
import java.util.Optional;

import com.terrascout.orchestrator.core.domain.Task;
import com.terrascout.orchestrator.core.enums.TaskStatusEnum;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 任务仓储（表 task；status 过滤 + idempotency_key 唯一注入点）。
 */
public interface TaskRepository extends JpaRepository<Task, String> {

    /** 幂等键查找（uk_task_idempotency 唯一）。 */
    Optional<Task> findByIdempotencyKey(String idempotencyKey);

    /** 按状态过滤分页。 */
    Page<Task> findByStatus(TaskStatusEnum status, Pageable pageable);

    /** 查找需要启动恢复扫描的任务（非终态且 heartbeat 过期，recoverOnStartup）。 */
    List<Task> findByStatusInAndHeartbeatAtLessThan(List<TaskStatusEnum> statuses, long heartbeatBefore);
}
