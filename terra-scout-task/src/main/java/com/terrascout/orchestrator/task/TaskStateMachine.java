package com.terrascout.orchestrator.task;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import com.terrascout.orchestrator.core.enums.TaskStatusEnum;

/**
 * 任务状态机（状态转移表全覆盖）。
 *
 * <p>纯函数式：不影响任何持久层，仅表达状态事件转移的合法性。非法转移抛
 * {@link IllegalStateException}；管理方（{@link TaskEngine} / 上层 Controller）据此
 * 映射为 409005 终态冲突等语义。
 *
 * <p>说明：QUEUED --START--> RUNNING 为"获取锁成功"路径；"获取锁失败"由引擎不执行转移、
 * 保持 QUEUED 表达（重新入队），不在表中另立状态。
 */
public final class TaskStateMachine {

    /** 状态机事件。 */
    public enum TaskEvent {
        ENQUEUE,
        START,
        PAUSE,
        RESUME,
        COMPLETE,
        FAIL,
        CANCEL,
        RETRY,
        ROLLBACK,
        ROLLBACK_DONE,
        ROLLBACK_FAIL
    }

    private static final Map<TaskStatusEnum, Map<TaskEvent, TaskStatusEnum>> TRANSITIONS = build();

    private TaskStateMachine() {
    }

    private static Map<TaskStatusEnum, Map<TaskEvent, TaskStatusEnum>> build() {
        Map<TaskStatusEnum, Map<TaskEvent, TaskStatusEnum>> t = new EnumMap<>(TaskStatusEnum.class);
        of(t, TaskStatusEnum.PENDING, TaskEvent.ENQUEUE, TaskStatusEnum.QUEUED);
        of(t, TaskStatusEnum.PENDING, TaskEvent.CANCEL, TaskStatusEnum.CANCELLED);

        of(t, TaskStatusEnum.QUEUED, TaskEvent.START, TaskStatusEnum.RUNNING);
        of(t, TaskStatusEnum.QUEUED, TaskEvent.CANCEL, TaskStatusEnum.CANCELLED);

        of(t, TaskStatusEnum.RUNNING, TaskEvent.PAUSE, TaskStatusEnum.PAUSED);
        of(t, TaskStatusEnum.RUNNING, TaskEvent.COMPLETE, TaskStatusEnum.SUCCESS);
        of(t, TaskStatusEnum.RUNNING, TaskEvent.FAIL, TaskStatusEnum.FAILED);
        of(t, TaskStatusEnum.RUNNING, TaskEvent.CANCEL, TaskStatusEnum.CANCELLED);

        of(t, TaskStatusEnum.PAUSED, TaskEvent.RESUME, TaskStatusEnum.RUNNING);
        of(t, TaskStatusEnum.PAUSED, TaskEvent.CANCEL, TaskStatusEnum.CANCELLED);

        of(t, TaskStatusEnum.FAILED, TaskEvent.RETRY, TaskStatusEnum.QUEUED);
        of(t, TaskStatusEnum.FAILED, TaskEvent.ROLLBACK, TaskStatusEnum.ROLLING_BACK);
        of(t, TaskStatusEnum.FAILED, TaskEvent.CANCEL, TaskStatusEnum.CANCELLED);

        of(t, TaskStatusEnum.ROLLING_BACK, TaskEvent.ROLLBACK_DONE, TaskStatusEnum.ROLLED_BACK);
        of(t, TaskStatusEnum.ROLLING_BACK, TaskEvent.ROLLBACK_FAIL, TaskStatusEnum.FAILED);

        of(t, TaskStatusEnum.ROLLED_BACK, TaskEvent.RETRY, TaskStatusEnum.QUEUED);
        return t;
    }

    private static void of(Map<TaskStatusEnum, Map<TaskEvent, TaskStatusEnum>> t,
                           TaskStatusEnum from, TaskEvent event, TaskStatusEnum to) {
        t.computeIfAbsent(from, k -> new EnumMap<>(TaskEvent.class)).put(event, to);
    }

    /**
     * 当前状态是否允许该事件（转移是否合法）。
     */
    public static boolean can(TaskStatusEnum current, TaskEvent event) {
        Objects.requireNonNull(current, "current 不能为 null");
        Objects.requireNonNull(event, "event 不能为 null");
        Map<TaskEvent, TaskStatusEnum> byEvent = TRANSITIONS.get(current);
        return byEvent != null && byEvent.containsKey(event);
    }

    /**
     * 执行转移；非法转移抛 {@link IllegalStateException}（不落状态）。
     */
    public static TaskStatusEnum next(TaskStatusEnum current, TaskEvent event) {
        if (!can(current, event)) {
            throw new IllegalStateException(
                    "非法状态转移: " + current + " --" + event + "--> 不允许");
        }
        return TRANSITIONS.get(current).get(event);
    }
}
