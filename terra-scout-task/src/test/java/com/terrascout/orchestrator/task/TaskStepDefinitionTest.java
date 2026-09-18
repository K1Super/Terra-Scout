package com.terrascout.orchestrator.task;

import static com.terrascout.orchestrator.task.TaskStepDefinition.BIND_ENV;
import static com.terrascout.orchestrator.task.TaskStepDefinition.CREATE_ISOLATION;
import static com.terrascout.orchestrator.task.TaskStepDefinition.DETECT_PROJECT;
import static com.terrascout.orchestrator.task.TaskStepDefinition.INSTALL_DEPENDENCIES;
import static com.terrascout.orchestrator.task.TaskStepDefinition.INSTALL_SDK_GO;
import static com.terrascout.orchestrator.task.TaskStepDefinition.INSTALL_SDK_JAVA;
import static com.terrascout.orchestrator.task.TaskStepDefinition.INSTALL_SDK_NODE;
import static com.terrascout.orchestrator.task.TaskStepDefinition.INSTALL_SDK_PYTHON;
import static com.terrascout.orchestrator.task.TaskStepDefinition.MATCH_VERSION;
import static com.terrascout.orchestrator.task.TaskStepDefinition.PARSE_MANIFEST;
import static com.terrascout.orchestrator.task.TaskStepDefinition.SDK_INSTALL_TIMEOUT_SECONDS;
import static com.terrascout.orchestrator.task.TaskStepDefinition.VERIFY_PROJECT;
import static com.terrascout.orchestrator.task.TaskStepDefinition.VERIFY_TIMEOUT_SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 步骤定义与 PROJECT_ASSEMBLE 装配表测试（task-step-definition.md 5.2、D-018）。
 */
class TaskStepDefinitionTest {

    @Test
    void projectAssembleHasElevenStepsInOrder() {
        List<TaskStepDefinition> steps = TaskStepDefinition.projectAssemble();
        assertThat(steps).hasSize(11);
        assertThat(steps).extracting(TaskStepDefinition::name).containsExactly(
                DETECT_PROJECT, PARSE_MANIFEST, MATCH_VERSION,
                INSTALL_SDK_JAVA, INSTALL_SDK_NODE, INSTALL_SDK_GO, INSTALL_SDK_PYTHON,
                CREATE_ISOLATION, INSTALL_DEPENDENCIES, BIND_ENV, VERIFY_PROJECT);
    }

    @Test
    void sdkAndVerifyTimeoutsFollowCommons() {
        for (TaskStepDefinition s : TaskStepDefinition.projectAssemble()) {
            switch (s.name()) {
                case INSTALL_SDK_JAVA, INSTALL_SDK_NODE, INSTALL_SDK_GO, INSTALL_SDK_PYTHON,
                        INSTALL_DEPENDENCIES ->
                    assertThat(s.timeoutSeconds()).isEqualTo(SDK_INSTALL_TIMEOUT_SECONDS);
                case VERIFY_PROJECT ->
                    assertThat(s.timeoutSeconds()).isEqualTo(VERIFY_TIMEOUT_SECONDS);
                default -> { /* 短超时步骤不在此断言 */ }
            }
        }
    }

    @Test
    void sdkInstallStepsArePausableAndRollbackable() {
        for (TaskStepDefinition s : TaskStepDefinition.projectAssemble()) {
            if (s.name().equals(INSTALL_SDK_JAVA) || s.name().equals(INSTALL_SDK_NODE)
                    || s.name().equals(INSTALL_SDK_GO) || s.name().equals(INSTALL_SDK_PYTHON)
                    || s.name().equals(INSTALL_DEPENDENCIES)) {
                assertThat(s.pausable()).isTrue();
                assertThat(s.hasRollback()).isTrue();
            }
        }
    }

    @Test
    void nullNameRejected() {
        assertThatThrownBy(() -> new TaskStepDefinition(null, 10, false, true, false))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void blankNameRejected() {
        assertThatThrownBy(() -> new TaskStepDefinition("   ", 10, false, true, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveTimeoutRejected() {
        assertThatThrownBy(() -> new TaskStepDefinition("X", 0, false, true, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
