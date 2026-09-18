package com.terrascout.orchestrator.download;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个下载任务（task 引擎安装步骤使用）：可取消、可上报进度的下载单元。
 *
 * <p>取消语义：{@link #cancel()} 置位取消标记，下载循环在下一块写入前中止并抛
 * {@link java.util.concurrent.CancellationException}，已完成分片保留为 .part 供续传。
 */
public final class DownloadTask implements ProgressListener, AutoCloseable {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicLong downloaded = new AtomicLong(0L);

    private final String url;
    private final String expectedSha256;
    private final Path targetFile;
    private final Path diskCheckRoot;
    private final Downloader downloader;

    private volatile DownloadResult result;

    /**
     * 使用默认下载器构造。
     */
    public DownloadTask(String url, String expectedSha256, Path targetFile, Path diskCheckRoot) {
        this(url, expectedSha256, targetFile, diskCheckRoot, new Downloader());
    }

    /**
     * 注入下载器（测试用）。
     */
    public DownloadTask(String url, String expectedSha256, Path targetFile, Path diskCheckRoot,
                        Downloader downloader) {
        this.url = Objects.requireNonNull(url, "url 不能为 null");
        this.expectedSha256 = Objects.requireNonNull(expectedSha256, "expectedSha256 不能为 null");
        this.targetFile = Objects.requireNonNull(targetFile, "targetFile 不能为 null");
        this.diskCheckRoot = Objects.requireNonNull(diskCheckRoot, "diskCheckRoot 不能为 null");
        this.downloader = Objects.requireNonNull(downloader, "downloader 不能为 null");
    }

    /**
     * 执行下载并校验。
     *
     * @return 下载结果
     */
    public DownloadResult run() {
        result = downloader.download(url, expectedSha256, targetFile, diskCheckRoot, this);
        return result;
    }

    /**
     * 请求取消（幂等）。
     */
    public void cancel() {
        cancelled.set(true);
    }

    /**
     * 当前已下载字节数（上次进度回调值）。
     */
    public long downloadedBytes() {
        return downloaded.get();
    }

    /**
     * 最近一次执行的结果；未执行过为 null。
     */
    public DownloadResult result() {
        return result;
    }

    // ---- ProgressListener ----

    @Override
    public void onProgress(long totalBytes, long bytesDownloaded) {
        downloaded.set(bytesDownloaded);
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void close() {
        // 无堆外/连接资源；实现 AutoCloseable 以满足遍历/关闭语义。
    }
}
