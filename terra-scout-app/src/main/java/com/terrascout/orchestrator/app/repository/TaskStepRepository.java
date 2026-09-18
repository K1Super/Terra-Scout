package com.terrascout.orchestrator.app.repository;

import java.util.List;
import java.util.Optional;

import com.terrascout.orchestrator.core.domain.TaskStep;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 任务步骤仓储（表 task_step；（task_id, step_index）唯一）。
 */
public interface TaskStepRepository extends JpaRepository<TaskStep, String> {

    /** 按任务取全部步骤（step_index 升序），供任务详情与日志（rest-schema 3.4.7/3.4.8）。 */
    List<TaskStep> findByTaskIdOrderByStepIndex(String taskId);

    /** 按任务 + 步骤序号取单条（编排落库 upsert 用）。 */
    Optional<TaskStep> findByTaskIdAndStepIndex(String taskId, int stepIndex);
}
