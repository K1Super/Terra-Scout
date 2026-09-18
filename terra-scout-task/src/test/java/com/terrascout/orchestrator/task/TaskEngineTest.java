package com.terrascout.orchestrator.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.terrascout.orchestrator.core.enums.StepStatusEnum;
import com.terrascout.orchestrator.core.enums.TaskStatusEnum;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

import org.junit.jupiter.api.Test;

/**
 * 任务执行引擎测试（state-machine.md 4.6 / 4.7）：happy path / 重试 / 回滚 / 锁争用 / 心跳自报。
 */
class TaskEngineTest {

    private static TaskStepDefinition def(String name, boolean hasRollback) {
        return new TaskStepDefinition(name, 60, true, true, hasRollback);
    }

    private static TaskEngine.TaskStepResolver resolver(StubExecutor... executors) {
        Map<String, TaskStepExecutor> m = new LinkedHashMap<>();
        for (StubExecutor e : executors) {
            m.put(e.name, e);
        }
        return m::get;
    }

    /** 可编程步骤执行器。 */
    static final class StubExecutor implements TaskStepExecutor {
        final String name;
        StepStatusEnum outcome = StepStatusEnum.SUCCESS;
        boolean retryable = true;
        RuntimeException rollbackFailure;
        int failFirst = 0;          // 前 N 次执行返回 FAILED，之后返回 outcome
        long sleepMs = 0;           // 每次 execute 阻塞时长，便于心跳自报观测
        final AtomicInteger execCount = new AtomicInteger();
        final AtomicInteger rollbackCount = new AtomicInteger();

        StubExecutor(String name) {
            this.name = name;
        }

        @Override
        public StepStatusEnum execute(TaskStepContext context) {
            execCount.incrementAndGet();
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return failFirst-- > 0 ? StepStatusEnum.FAILED : outcome;
        }

        @Override
        public void rollback(TaskStepContext context) {
            rollbackCount.incrementAndGet();
            if (rollbackFailure != null) {
                throw rollbackFailure;
            }
        }

        @Override public boolean isPausable() { return true; }
        @Override public boolean isRetryable() { return retryable; }
        @Override public int getTimeoutSeconds() { return 60; }
    }

    @Test
    void happyPathAllSuccess() throws Exception {
        List<TaskStepDefinition> steps = List.of(def("a", true), def("b", true));
        StubExecutor ea = new StubExecutor("a");
        StubExecutor eb = new StubExecutor("b");
        try (TaskEngine engine = new TaskEngine()) {
            TaskEngine.TaskExecutionResult r =
                    engine.execute("p1", steps, resolver(ea, eb), null, 1);
            assertThat(r.status()).isEqualTo(TaskStatusEnum.SUCCESS);
            assertThat(r.stepsSucceeded()).isEqualTo(2);
            assertThat(r.rolledBack()).isFalse();
            assertThat(r.errorCode()).isNull();
        }
    }

    @Test
    void skippedCountsAsSuccess() throws Exception {
        List<TaskStepDefinition> steps = List.of(def("a", true), def("b", true));
        StubExecutor ea = new StubExecutor("a");
        StubExecutor eb = new StubExecutor("b");
        eb.outcome = StepStatusEnum.SKIPPED;
        try (TaskEngine engine = new TaskEngine()) {
            TaskEngine.TaskExecutionResult r =
                    engine.execute("p1", steps, resolver(ea, eb), null, 1);
            assertThat(r.status()).isEqualTo(TaskStatusEnum.SUCCESS);
            assertThat(r.stepsSucceeded()).isEqualTo(2);
        }
    }

    @Test
    void emptyStepsReturnSuccessWithoutLock() throws Exception {
        try (TaskEngine engine = new TaskEngine()) {
            TaskEngine.TaskExecutionResult r =
                    engine.execute("p1", List.of(), resolver(), null, 1);
            assertThat(r.status()).isEqualTo(TaskStatusEnum.SUCCESS);
            assertThat(r.stepsSucceeded()).isZero();
        }
    }

    @Test
    void transientFailureRetriesThenSucceeds() throws Exception {
        List<TaskStepDefinition> steps = List.of(def("a", true), def("b", true));
        StubExecutor ea = new StubExecutor("a");
        StubExecutor eb = new StubExecutor("b");
        ea.failFirst = 2; // 前两次失败，第三次成功
        try (TaskEngine engine = new TaskEngine()) {
            TaskEngine.TaskExecutionResult r =
                    engine.execute("p1", steps, resolver(ea, eb), null, 3);
            assertThat(r.status()).isEqualTo(TaskStatusEnum.SUCCESS);
            assertThat(ea.execCount.get()).isEqualTo(3);
            assertThat(r.stepsSucceeded()).isEqualTo(2);
        }
    }

    @Test
    void retryExhaustionRollsBackPriorSteps() throws Exception {
        List<TaskStepDefinition> steps = List.of(def("a", true), def("b", true));
        StubExecutor ea = new StubExecutor("a");
        StubExecutor eb = new StubExecutor("b");
        eb.outcome = StepStatusEnum.FAILED; // 持续失败
        try (TaskEngine engine = new TaskEngine()) {
            TaskEngine.TaskExecutionResult r =
                    engine.execute("p1", steps, resolver(ea, eb), null, 2);
            // a 成功计数；b 重试一次后耗尽 → 逆序回滚 a
            assertThat(r.status()).isEqualTo(TaskStatusEnum.ROLLED_BACK);
            assertThat(r.rolledBack()).isTrue();
            assertThat(r.stepsSucceeded()).isEqualTo(1);
            assertThat(eb.execCount.get()).isEqualTo(2);
            assertThat(ea.rollbackCount.get()).isEqualTo(1);
        }
    }

    @Test
    void nonRetryableFailureTriggersRollback() throws Exception {
        List<TaskStepDefinition> steps = List.of(def("a", true), def("b", true));
        StubExecutor ea = new StubExecutor("a");
        StubExecutor eb = new StubExecutor("b");
        eb.outcome = StepStatusEnum.FAILED;
        eb.retryable = false; // 不可重试 → 直接回滚
        try (TaskEngine engine = new TaskEngine()) {
            TaskEngine.TaskExecutionResult r =
                    engine.execute("p1", steps, resolver(ea, eb), null, 5);
            assertThat(r.status()).isEqualTo(TaskStatusEnum.ROLLED_BACK);
            assertThat(eb.execCount.get()).isEqualTo(1); // 不重试
            assertThat(ea.rollbackCount.get()).isEqualTo(1);
        }
    }

    @Test
    void rollbackFailureMapsTo500004() throws Exception {
        List<TaskStepDefinition> steps = List.of(def("a", true), def("b", true));
        StubExecutor ea = new StubExecutor("a");
        StubExecutor eb = new StubExecutor("b");
        eb.outcome = StepStatusEnum.FAILED;
        ea.rollbackFailure = new RuntimeException("boom");
        try (TaskEngine engine = new TaskEngine()) {
            TaskEngine.TaskExecutionResult r =
                    engine.execute("p1", steps, resolver(ea, eb), null, 1);
            assertThat(r.status()).isEqualTo(TaskStatusEnum.FAILED);
            assertThat(r.rolledBack()).isFalse();
            assertThat(r.errorCode()).isEqualTo(TerraScoutError.ROLLBACK_FAILED.getCode());
        }
    }

    @Test
    void missingResolverThrows() throws Exception {
        List<TaskStepDefinition> steps = List.of(def("x", true));
        try (TaskEngine engine = new TaskEngine()) {
            assertThatThrownBy(() -> engine.execute("p1", steps, resolver(), null, 1))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void lockContentionThrows409001() throws Exception {
        LockManager lm = new LockManager();
        TaskEngine engine = new TaskEngine(lm, 100, 1_000);
        List<TaskStepDefinition> steps = List.of(def("a", true));
        StubExecutor ea = new StubExecutor("a");

        CountDownLatch acquired = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                lm.tryLock("p1", 1_000);
                acquired.countDown();
                Thread.sleep(2_000); // 持锁
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        assertThat(acquired.await(2, TimeUnit.SECONDS)).isTrue();

        try {
            assertThatThrownBy(() -> engine.execute("p1", steps, resolver(ea), null, 1))
                    .isInstanceOf(TerraScoutException.class)
                    .satisfies(ex -> assertThat(((TerraScoutException) ex).getError())
                            .isEqualTo(TerraScoutError.PROJECT_LOCKED));
        } finally {
            holder.interrupt();
            holder.join();
            engine.close();
        }
    }

    @Test
    void heartbeatSelfReportsDuringExecution() throws Exception {
        AtomicInteger beats = new AtomicInteger();
        TaskEngine.Heartbeat heartbeat = beats::incrementAndGet;
        List<TaskStepDefinition> steps = List.of(def("a", true));
        StubExecutor ea = new StubExecutor("a");
        ea.sleepMs = 150; // 执行期 ≥150ms，让心跳线程多次自报
        try (TaskEngine engine = new TaskEngine(new LockManager(), 1_000, 5)) {
            TaskEngine.TaskExecutionResult r =
                    engine.execute("p1", steps, resolver(ea), heartbeat, 1);
            assertThat(r.status()).isEqualTo(TaskStatusEnum.SUCCESS);
            assertThat(beats.get()).isGreaterThan(0);
        }
    }
}
