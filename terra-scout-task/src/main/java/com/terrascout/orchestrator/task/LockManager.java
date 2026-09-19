package com.terrascout.orchestrator.task;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按 projectId 分锁。
 *
 * <p>同一项目任务串行、不同项目并行；锁等待超时由管理方映射为 409001 PROJECT_LOCKED。
 * locks map 随项目数线性增长，桌面规模（&lt;1000 项目）可接受，不做过期清理。
 */
public final class LockManager {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** 取/建指定项目锁。 */
    public ReentrantLock getLock(String projectId) {
        return locks.computeIfAbsent(Objects.requireNonNull(projectId, "projectId 不能为 null"),
                k -> new ReentrantLock(true));
    }

    /**
     * 尝试在超时内获得锁。
     *
     * @return true 获取成功；false 超时未获锁
     */
    public boolean tryLock(String projectId, long timeoutMs) throws InterruptedException {
        return getLock(projectId).tryLock(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 释放锁；仅当当前线程持有该锁时解锁（幂等、安全）。
     */
    public void unlock(String projectId) {
        ReentrantLock lock = locks.get(projectId);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    /** 当前持有的项目锁数目（测试观测）。 */
    public int lockCount() {
        return locks.size();
    }
}
