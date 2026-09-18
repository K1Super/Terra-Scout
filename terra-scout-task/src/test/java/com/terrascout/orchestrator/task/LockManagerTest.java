package com.terrascout.orchestrator.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * 按项目分锁测试（concurrency-model.md 6.2）：同锁复用 / 跨线程互斥 / 幂等解锁。
 */
class LockManagerTest {

    private final LockManager lm = new LockManager();

    @Test
    void sameProjectReturnsSameLockInstance() {
        assertThat(lm.getLock("p1")).isSameAs(lm.getLock("p1"));
    }

    @Test
    void differentProjectsReturnDistinctLocks() {
        assertThat(lm.getLock("p1")).isNotSameAs(lm.getLock("p2"));
    }

    @Test
    void nullProjectRejected() {
        assertThatThrownBy(() -> lm.getLock(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void crossThreadContentionTimesOut() throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                lm.tryLock("p1", 1_000);
                acquired.countDown();
                Thread.sleep(300); // 持锁 300ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        assertThat(acquired.await(2, TimeUnit.SECONDS)).isTrue();
        // 主线程 50ms 内拿不到锁 → 超时返回 false
        assertThat(lm.tryLock("p1", 50)).isFalse();
        holder.join();
    }

    @Test
    void unlockFromNonOwningThreadHasNoEffect() throws Exception {
        assertThat(lm.tryLock("p1", 100)).isTrue();
        CountDownLatch done = new CountDownLatch(1);
        Thread other = new Thread(() -> {
            lm.unlock("p1"); // 非持有线程解锁 → 应被忽略
            done.countDown();
        });
        other.start();
        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        // 本线程仍持有 → 可重入（tryLock(0) 立即成功）
        assertThat(lm.tryLock("p1", 0)).isTrue();
        lm.unlock("p1"); // 释放第二层
        lm.unlock("p1"); // 释放第一层
        other.join();
    }

    @Test
    void unlockWhenNeverHeldIsIdempotent() {
        lm.unlock("p1");      // 从未持有 → 无副作用
        lm.unlock("missing"); // 无此锁 → 无副作用
    }

    @Test
    void unlockWhenLockAbsentIsSafe() {
        lm.unlock("nonexistent");
    }

    @Test
    void lockCountTracksCreatedLocks() {
        int before = lm.lockCount();
        lm.getLock("pX");
        assertThat(lm.lockCount()).isEqualTo(before + 1);
    }
}
