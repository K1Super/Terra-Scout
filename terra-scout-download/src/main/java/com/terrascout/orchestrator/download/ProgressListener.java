package com.terrascout.orchestrator.download;

/**
 * 下载进度监听（{@link ResumeableDownloader} 回调）。
 *
 * <p>用于：进度上报、取消协作（下载循环每块写入前查询 {@link #isCancelled()}）。
 */
@FunctionalInterface
public interface ProgressListener {

    /**
     * 进度回调。
     *
     * @param totalBytes      总字节数（可知时可正数；未知为 0）
     * @param bytesDownloaded 已下载字节数
     */
    void onProgress(long totalBytes, long bytesDownloaded);

    /**
     * 取消信号（默认 false 表示不可取消）。
     *
     * @return true 表示应立即中止下载（保留已完成分片以便续传）
     */
    default boolean isCancelled() {
        return false;
    }
}
