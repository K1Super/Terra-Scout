package com.terrascout.orchestrator.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 心跳超时检测测试：自报心跳超时判定。
 */
class HeartbeatMonitorTest {

    /** 内存 store：记录查询的 deadline 与标记的任务。 */
    static final class FakeStore implements HeartbeatMonitor.HeartbeatStore {
        final List<String> found = new ArrayList<>();
        final List<String> marked = new ArrayList<>();
        long lastDeadline;

        @Override
        public List<String> findStaleRunning(long heartbeatBefore) {
            this.lastDeadline = heartbeatBefore;
            return found;
        }

        @Override
        public void markHeartbeatTimeout(String taskId) {
            marked.add(taskId);
        }
    }

    @Test
    void nullStoreRejected() {
        HeartbeatMonitor m = new HeartbeatMonitor();
        assertThatThrownBy(() -> m.detectTimeout(null, 1_000L))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void staleTasksMarkedAndCounted() {
        FakeStore store = new FakeStore();
        store.found.add("t1");
        store.found.add("t2");

        HeartbeatMonitor m = new HeartbeatMonitor();
        assertThat(m.detectTimeout(store, 100_000L)).isEqualTo(2);
        assertThat(store.marked).containsExactly("t1", "t2");
    }

    @Test
    void deadlinePassedIsNowMinusStaleThreshold() {
        FakeStore store = new FakeStore();
        long now = 300_000L;
        new HeartbeatMonitor().detectTimeout(store, now);
        assertThat(store.lastDeadline)
                .isEqualTo(now - HeartbeatMonitor.STALE_THRESHOLD_MS);
    }

    @Test
    void noStaleTasksReturnsZero() {
        FakeStore store = new FakeStore();
        assertThat(new HeartbeatMonitor().detectTimeout(store, 1_000L)).isZero();
        assertThat(store.marked).isEmpty();
    }

    @Test
    void recoverOnStartupRunsSameDetection() {
        FakeStore store = new FakeStore();
        store.found.add("x");
        assertThat(new HeartbeatMonitor().recoverOnStartup(store)).isEqualTo(1);
        assertThat(store.marked).containsExactly("x");
    }
}
