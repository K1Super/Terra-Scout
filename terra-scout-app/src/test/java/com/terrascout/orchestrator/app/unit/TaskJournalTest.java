package com.terrascout.orchestrator.app.unit;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.terrascout.orchestrator.app.repository.CommandExecutionRepository;
import com.terrascout.orchestrator.app.repository.TaskRepository;
import com.terrascout.orchestrator.app.repository.TaskStepRepository;
import com.terrascout.orchestrator.app.service.TaskJournal;
import com.terrascout.orchestrator.core.domain.CommandExecution;
import com.terrascout.orchestrator.core.domain.Task;
import com.terrascout.orchestrator.core.domain.TaskStep;
import com.terrascout.orchestrator.core.dto.CommandSpec;
import com.terrascout.orchestrator.core.enums.CommandStatusEnum;
import com.terrascout.orchestrator.core.enums.StepStatusEnum;
import com.terrascout.orchestrator.core.enums.TaskStatusEnum;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务日志落库组件单测：任务 / 步骤 / 命令三类实体的状态推进。
 */
class TaskJournalTest {

    private final TaskRepository taskRepo = mock(TaskRepository.class);
    private final TaskStepRepository stepRepo = mock(TaskStepRepository.class);
    private final CommandExecutionRepository cmdRepo = mock(CommandExecutionRepository.class);
    private final TaskJournal journal = new TaskJournal(taskRepo, stepRepo, cmdRepo, new ObjectMapper());

    @BeforeEach
    void stubRequiredTask() {
        Task task = new Task();
        task.setId("t1");
        when(taskRepo.findById("t1")).thenReturn(Optional.of(task));
    }

    private void stubExistingStep(int index, TaskStep step) {
        when(stepRepo.findByTaskIdAndStepIndex("t1", index)).thenReturn(Optional.of(step));
    }

    @Test
    void markRunningUpdatesStatusAndOwner() {
        journal.markRunning("t1", "owner");
        Task task = taskRepo.findById("t1").orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatusEnum.RUNNING);
        assertThat(task.getLockOwner()).isEqualTo("owner");
        verify(taskRepo).save(task);
    }

    @Test
    void heartbeatUpdatesTimestamp() {
        journal.heartbeat("t1");
        verify(taskRepo).save(any(Task.class));
    }

    @Test
    void stepStartedCreatesNewStep() {
        when(stepRepo.findByTaskIdAndStepIndex("t1", 0)).thenReturn(Optional.empty());
        journal.stepStarted("t1", 0, "DETECT_PROJECT");
        verify(stepRepo).save(any(TaskStep.class));
    }

    @Test
    void stepStartedReusesExistingStep() {
        TaskStep step = new TaskStep();
        step.setId("s1");
        stubExistingStep(0, step);
        journal.stepStarted("t1", 0, "DETECT_PROJECT");
        assertThat(step.getStatus()).isEqualTo(StepStatusEnum.RUNNING);
        verify(stepRepo).save(step);
    }

    @Test
    void stepFinishedWritesOutput() {
        TaskStep step = new TaskStep();
        step.setId("s1");
        stubExistingStep(1, step);
        journal.stepFinished("t1", 1, StepStatusEnum.SUCCESS, Map.of("k", "v"));
        assertThat(step.getStatus()).isEqualTo(StepStatusEnum.SUCCESS);
        assertThat(step.getOutputJson()).contains("k");
    }

    @Test
    void stepFailedWritesError() {
        TaskStep step = new TaskStep();
        step.setId("s1");
        stubExistingStep(1, step);
        journal.stepFailed("t1", 1, 422006, "boom");
        assertThat(step.getStatus()).isEqualTo(StepStatusEnum.FAILED);
        assertThat(step.getErrorCode()).isEqualTo(422006);
    }

    @Test
    void stepRolledBackWritesStatus() {
        TaskStep step = new TaskStep();
        step.setId("s1");
        stubExistingStep(2, step);
        journal.stepRolledBack("t1", 2);
        assertThat(step.getStatus()).isEqualTo(StepStatusEnum.ROLLED_BACK);
    }

    @Test
    void completeWritesTerminalState() {
        journal.complete("t1", TaskStatusEnum.FAILED, 0.0, 422006, "failed");
        Task task = taskRepo.findById("t1").orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatusEnum.FAILED);
        assertThat(task.getErrorCode()).isEqualTo(422006);
        verify(taskRepo).save(task);
    }

    @Test
    void recordCommandPersistsAudit() {
        CommandSpec spec = new CommandSpec();
        spec.setCommand("mvn");
        spec.setArgs(List.of("-B", "install"));
        journal.recordCommand("p1", spec, "C:\\proj", 0, CommandStatusEnum.SUCCESS, 1L, 2L);
        verify(cmdRepo).save(any(CommandExecution.class));
    }

    @Test
    void markRunningThrowsWhenTaskMissing() {
        when(taskRepo.findById("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> journal.markRunning("missing", "owner"))
                .isInstanceOf(IllegalStateException.class);
    }
}
