package com.terrascout.orchestrator.task;

import java.util.List;
import java.util.Objects;

/**
 * 心跳超时检测器（concurrency-model.md 6.4 / state-machine.md 4.5、4.9，D-007）。
 *
 * <p>设计原则：任务线程自报心跳，本检测器"只检测、不刷新"。检测到 RUNNING 且
 * heartbeat_at 早于阈值的任务即视为假死，标记 FAILED + 错误码 500003（任务心跳超时）。
 *
 * <p>持久化边界：通过 {@link HeartbeatStore} 端口抽象，具体读写由 app 模块（DB/JPA）实现，
 * 本模块可脱离数据库单测。
 */
public final class HeartbeatMonitor {

    /** 任务线程心跳更新间隔（毫秒，terrascout.task.heartbeat-interval）。 */
    public static final long HEARTBEAT_INTERVAL_MS = 5000L;
    /** 心跳超时阈值（毫秒，terrascout.task.heartbeat-timeout）。 */
    public static final long STALE_THRESHOLD_MS = 60_000L;
    /** 检测扫描周期（毫秒，terrascout.task.detect-interval）。 */
    public static final long DETECT_INTERVAL_MS = 30_000L;

    /** RUNNING 任务心跳存储端口（由上层实现，写入 H2）。 */
    @FunctionalInterface
    public interface RunningTaskStore {
        List<String> findStaleRunning(long heartbeatBefore);
    }

    /** 心跳存储端口：查假死 + 标记超时。 */
    public interface HeartbeatStore extends RunningTaskStore {
        void markHeartbeatTimeout(String taskId);
    }

    /**
     * 执行一次超时检测（用系统时钟）。
     *
     * @return 本次标记为超时的任务数目
     */
    public int detectTimeout(HeartbeatStore store) {
        return detectTimeout(store, System.currentTimeMillis());
    }

    /**
     * 执行一次超时检测（注入时钟，便于单测）。
     *
     * @param store 心跳存储
     * @param now   当前时刻（毫秒）
     * @return 本次标记为超时的任务数目
     */
    public int detectTimeout(HeartbeatStore store, long now) {
        Objects.requireNonNull(store, "store 不能为 null");
        long deadline = now - STALE_THRESHOLD_MS;
        List<String> stale = store.findStaleRunning(deadline);
        for (String taskId : stale) {
            store.markHeartbeatTimeout(taskId);
        }
        return stale.size();
    }

    /**
     * 启动恢复扫描（state-machine.md 4.9 / concurrency-model 6.5）：与常规检测同逻辑。
     */
    public int recoverOnStartup(HeartbeatStore store) {
        return detectTimeout(store, System.currentTimeMillis());
    }
}
