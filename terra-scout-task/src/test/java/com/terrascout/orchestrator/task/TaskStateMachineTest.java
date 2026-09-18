package com.terrascout.orchestrator.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.terrascout.orchestrator.core.enums.TaskStatusEnum.CANCELLED;
import static com.terrascout.orchestrator.core.enums.TaskStatusEnum.FAILED;
import static com.terrascout.orchestrator.core.enums.TaskStatusEnum.PAUSED;
import static com.terrascout.orchestrator.core.enums.TaskStatusEnum.PENDING;
import static com.terrascout.orchestrator.core.enums.TaskStatusEnum.QUEUED;
import static com.terrascout.orchestrator.core.enums.TaskStatusEnum.ROLLED_BACK;
import static com.terrascout.orchestrator.core.enums.TaskStatusEnum.ROLLING_BACK;
import static com.terrascout.orchestrator.core.enums.TaskStatusEnum.RUNNING;
import static com.terrascout.orchestrator.core.enums.TaskStatusEnum.SUCCESS;
import static com.terrascout.orchestrator.task.TaskStateMachine.TaskEvent.CANCEL;
import static com.terrascout.orchestrator.task.TaskStateMachine.TaskEvent.COMPLETE;
import static com.terrascout.orchestrator.task.TaskStateMachine.TaskEvent.ENQUEUE;
import static com.terrascout.orchestrator.task.TaskStateMachine.TaskEvent.FAIL;
import static com.terrascout.orchestrator.task.TaskStateMachine.TaskEvent.PAUSE;
import static com.terrascout.orchestrator.task.TaskStateMachine.TaskEvent.RESUME;
import static com.terrascout.orchestrator.task.TaskStateMachine.TaskEvent.RETRY;
import static com.terrascout.orchestrator.task.TaskStateMachine.TaskEvent.ROLLBACK;
import static com.terrascout.orchestrator.task.TaskStateMachine.TaskEvent.ROLLBACK_DONE;
import static com.terrascout.orchestrator.task.TaskStateMachine.TaskEvent.ROLLBACK_FAIL;
import static com.terrascout.orchestrator.task.TaskStateMachine.TaskEvent.START;

import org.junit.jupiter.api.Test;

import com.terrascout.orchestrator.core.enums.TaskStatusEnum;

/**
 * 状态机转移表全覆盖测试（state-machine.md 4.3 全部行 + 非法/终态拒绝）。
 */
class TaskStateMachineTest {

    @Test
    void fullTransferTableAllRows() {
        // 每行断言 can + next 结果。
        assertEdge(PENDING, ENQUEUE, QUEUED);
        assertEdge(PENDING, CANCEL, CANCELLED);

        assertEdge(QUEUED, START, RUNNING);
        assertEdge(QUEUED, CANCEL, CANCELLED);

        assertEdge(RUNNING, PAUSE, PAUSED);
        assertEdge(RUNNING, COMPLETE, SUCCESS);
        assertEdge(RUNNING, FAIL, FAILED);
        assertEdge(RUNNING, CANCEL, CANCELLED);

        assertEdge(PAUSED, RESUME, RUNNING);
        assertEdge(PAUSED, CANCEL, CANCELLED);

        assertEdge(FAILED, RETRY, QUEUED);
        assertEdge(FAILED, ROLLBACK, ROLLING_BACK);
        assertEdge(FAILED, CANCEL, CANCELLED);

        assertEdge(ROLLING_BACK, ROLLBACK_DONE, ROLLED_BACK);
        assertEdge(ROLLING_BACK, ROLLBACK_FAIL, FAILED);

        assertEdge(ROLLED_BACK, RETRY, QUEUED);
    }

    @Test
    void invalidTransitionsRejected() {
        assertNotAllowed(PENDING, START);
        assertNotAllowed(PENDING, COMPLETE);
        assertNotAllowed(PENDING, RETRY);
        assertNotAllowed(QUEUED, ENQUEUE);
        assertNotAllowed(QUEUED, COMPLETE);
        assertNotAllowed(RUNNING, ENQUEUE);
        assertNotAllowed(SUCCESS, RETRY);
        assertNotAllowed(CANCELLED, RESUME);
        // FAILED--RETRY 为合法边（→QUEUED），已在 fullTransferTableAllRows 覆盖，不在此列。
    }

    @Test
    void terminalStatesRejectAllEvents() {
        for (TaskStatusEnum terminal : new TaskStatusEnum[]{SUCCESS, FAILED, CANCELLED, ROLLED_BACK}) {
            for (TaskStateMachine.TaskEvent event : TaskStateMachine.TaskEvent.values()) {
                if (isLegalTerminalEdge(terminal, event)) {
                    continue;
                }
                assertThat(TaskStateMachine.can(terminal, event)).as(terminal + " --" + event).isFalse();
            }
        }
        // 终态上应用非法事件 → next 抛异常。
        assertThatThrownBy(() -> TaskStateMachine.next(SUCCESS, COMPLETE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nextOnInvalidTransitionThrows() {
        assertThatThrownBy(() -> TaskStateMachine.next(QUEUED, COMPLETE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullsRejected() {
        assertThatThrownBy(() -> TaskStateMachine.can(null, START))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TaskStateMachine.can(PENDING, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- 工具 ----

    private static void assertEdge(TaskStatusEnum from, TaskStateMachine.TaskEvent event, TaskStatusEnum to) {
        assertThat(TaskStateMachine.can(from, event)).isTrue();
        assertThat(TaskStateMachine.next(from, event)).isEqualTo(to);
    }

    private static void assertNotAllowed(TaskStatusEnum from, TaskStateMachine.TaskEvent event) {
        assertThat(TaskStateMachine.can(from, event)).isFalse();
    }

    private static boolean isLegalTerminalEdge(TaskStatusEnum terminal, TaskStateMachine.TaskEvent event) {
        return (terminal == FAILED && (event == RETRY || event == ROLLBACK || event == CANCEL))
                || (terminal == ROLLED_BACK && event == RETRY);
    }
}
