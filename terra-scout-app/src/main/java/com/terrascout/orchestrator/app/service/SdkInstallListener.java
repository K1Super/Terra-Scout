package com.terrascout.orchestrator.app.service;

/**
 * SDK 安装阶段回调（安装进度感知：下载字节进度 + 阶段流转）。
 *
 * <p>阶段取值：{@code STARTING}（复用校验）/ {@code DOWNLOADING}（下载，伴随
 * {@link #onProgress(long, long)} 字节回调）/ {@code EXTRACTING}（解压）/
 * {@code FINISHING}（写安装记录）/ {@code DONE}（完成）。实现类可只覆盖关心的方法。
 */
public interface SdkInstallListener {

    /** 空监听（同步调用方不关心进度时使用）。 */
    SdkInstallListener NOOP = new SdkInstallListener() {
        @Override
        public void onStage(String stage, String message) {
            // 空实现
        }

        @Override
        public void onProgress(long totalBytes, long bytesDownloaded) {
            // 空实现
        }
    };

    /**
     * 阶段流转回调。
     *
     * @param stage   阶段标识（英文常量，随 message 给用户中文文案）
     * @param message 阶段文案
     */
    default void onStage(String stage, String message) {
        // 默认忽略
    }

    /**
     * 下载进度回调（DOWNLOADING 阶段内多次触发）。
     *
     * @param totalBytes      总字节数（未知为 0）
     * @param bytesDownloaded 已下载字节数
     */
    default void onProgress(long totalBytes, long bytesDownloaded) {
        // 默认忽略
    }

    /**
     * 取消信号：返回 true 时安装器在最近检查点（下载块 / 阶段边界）中止，
     * 并清理下载暂存（.part）与半解压目录后取消终态收敛。默认 false 表示不可取消。
     *
     * @return true 表示应立即中止安装
     */
    default boolean isCancelled() {
        return false;
    }
}
