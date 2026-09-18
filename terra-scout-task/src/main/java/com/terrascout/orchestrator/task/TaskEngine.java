package com.terrascout.orchestrator.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.terrascout.orchestrator.core.enums.StepStatusEnum;
import com.terrascout.orchestrator.core.enums.TaskStatusEnum;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

/**
 * 任务执行引擎（state-machine.md 4.3 / 4.6 / 4.7，concurrency-model 6.4）。
 *
 * <p>职责：按步骤定义依次执行；失败按 maxRetry 重试；重试耗尽时对已完成的、具备补偿的步骤
 * 逆序回滚；任务执行期间由本线程自报心跳（D-007，经注入的 {@link Heartbeat} 回调）。
 *
 * <p>持久化边界：本引擎不落库，最终状态由调用方（app 层）写入；锁争用抛 409001。
 */
public final class TaskEngine implements AutoCloseable {

    /** 心跳自报回调（由 app 层连到 DB 心跳更新）。 */
    public interface Heartbeat {
        void beat();
    }

    /** 步骤执行器解析：按步骤名返回执行器实例。 */
    public interface TaskStepResolver {
        TaskStepExecutor resolve(String stepName);
    }

    /** 引擎执行结果。 */
    public record TaskExecutionResult(
            TaskStatusEnum status,
            int stepsSucceeded,
            boolean rolledBack,
            Integer errorCode) {
    }

    private final LockManager lockManager;
    private final long lockTimeoutMs;
    private final long heartbeatIntervalMs;
    private final ScheduledThreadPoolExecutor scheduler;

    /** 默认：锁超时 15s、心跳间隔 1s。 */
    public TaskEngine() {
        this(new LockManager(), 15_000L, 1_000L);
    }

    /**
     * @param lockManager          按项目分锁
     * @param lockTimeoutMs        获取项目锁的等待超时（毫秒）
     * @param heartbeatIntervalMs  心跳自报间隔（毫秒）
     */
    public TaskEngine(LockManager lockManager, long lockTimeoutMs, long heartbeatIntervalMs) {
        this.lockManager = Objects.requireNonNull(lockManager, "lockManager 不能为 null");
        this.lockTimeoutMs = lockTimeoutMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "terra-scout-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 执行任务全部步骤。
     *
     * @param projectId 项目 ID（用于分锁）
     * @param steps     步骤定义序列
     * @param resolver  步骤执行器解析器
     * @param heartbeat 心跳自报回调（可为 null）
     * @param maxRetry  最大执行次数（重试阈值；≥1）
     * @return 执行结果（终态）
     * @throws TerraScoutException   409001 项目锁获取超时
     * @throws InterruptedException 锁等待被中断
     */
    public TaskExecutionResult execute(String projectId, List<TaskStepDefinition> steps,
                                       TaskStepResolver resolver, Heartbeat heartbeat, int maxRetry)
            throws InterruptedException {
        Objects.requireNonNull(steps, "steps 不能为 null");
        Objects.requireNonNull(resolver, "resolver 不能为 null");
        if (steps.isEmpty()) {
            return new TaskExecutionResult(TaskStatusEnum.SUCCESS, 0, false, null);
        }
        if (!lockManager.tryLock(projectId, lockTimeoutMs)) {
            throw new TerraScoutException(TerraScoutError.PROJECT_LOCKED,
                    "获取项目锁超时: " + projectId);
        }
        ScheduledFuture<?> heartbeatTask = startHeartbeat(heartbeat);
        try {
            return runSteps(projectId, steps, resolver, maxRetry);
        } finally {
            if (heartbeatTask != null) {
                heartbeatTask.cancel(false);
                scheduler.purge();
            }
            lockManager.unlock(projectId);
        }
    }

    private TaskExecutionResult runSteps(String projectId, List<TaskStepDefinition> steps,
                                         TaskStepResolver resolver, int maxRetry) {
        List<TaskStepDefinition> completedDefs = new ArrayList<>();
        List<TaskStepContext> completedCtxs = new ArrayList<>();
        int succeeded = 0;
        for (TaskStepDefinition def : steps) {
            TaskStepExecutor executor = resolver.resolve(def.name());
            if (executor == null) {
                throw new IllegalStateException("缺少步骤执行器: " + def.name());
            }
            int attempts = 0;
            while (true) {
                TaskStepContext ctx = new TaskStepContext().setProjectId(projectId);
                StepStatusEnum outcome = executor.execute(ctx);
                if (outcome == StepStatusEnum.SUCCESS || outcome == StepStatusEnum.SKIPPED) {
                    succeeded++;
                    completedDefs.add(def);
                    completedCtxs.add(ctx);
                    break;
                }
                attempts++;
                if (attempts < maxRetry && executor.isRetryable()) {
                    continue; // 失败重试（FAILED --RETRY--> QUEUED）
                }
                return rollback(completedDefs, completedCtxs, resolver, succeeded);
            }
        }
        return new TaskExecutionResult(TaskStatusEnum.SUCCESS, succeeded, false, null);
    }

    private TaskExecutionResult rollback(List<TaskStepDefinition> completedDefs,
                                         List<TaskStepContext> completedCtxs,
                                         TaskStepResolver resolver, int succeeded) {
        for (int k = completedDefs.size() - 1; k >= 0; k--) {
            TaskStepDefinition def = completedDefs.get(k);
            if (!def.hasRollback()) {
                continue;
            }
            TaskStepExecutor executor = resolver.resolve(def.name());
            if (executor == null) {
                continue;
            }
            try {
                executor.rollback(completedCtxs.get(k));
            } catch (RuntimeException e) {
                return new TaskExecutionResult(TaskStatusEnum.FAILED, succeeded, false,
                        TerraScoutError.ROLLBACK_FAILED.getCode());
            }
        }
        return new TaskExecutionResult(TaskStatusEnum.ROLLED_BACK, succeeded, true, null);
    }

    private ScheduledFuture<?> startHeartbeat(Heartbeat heartbeat) {
        if (heartbeat == null) {
            return null;
        }
        return scheduler.scheduleAtFixedRate(heartbeat::beat,
                heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    }

    /** 关闭心跳调度线程。 */
    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
