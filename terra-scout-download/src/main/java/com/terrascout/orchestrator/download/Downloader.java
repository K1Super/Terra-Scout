package com.terrascout.orchestrator.download;

import java.nio.file.Path;

/**
 * 下载编排门面：断点续传下载 + SHA-256 校验（security.md 6.6）。
 *
 * <p>组合 {@link ResumeableDownloader} 与 {@link Sha256Verifier}；校验失败抛 422009。
 * 下载/解压的安全红线（master-plan §8）中下载侧语义均在此收敛。
 */
public final class Downloader {

    private final ResumeableDownloader resumeable;

    public Downloader() {
        this(new ResumeableDownloader());
    }

    /**
     * 注入下载器（测试注入短超时/定向 MockWebServer）。
     *
     * @param resumeable 断点续传下载器
     */
    public Downloader(ResumeableDownloader resumeable) {
        this.resumeable = java.util.Objects.requireNonNull(resumeable, "resumeable 不能为 null");
    }

    /**
     * 下载并校验。
     *
     * @param url           下载地址
     * @param expectedSha256 期望的 64 位十六进制摘要
     * @param targetFile    目标文件
     * @param diskCheckRoot 磁盘检查根目录
     * @param listener      进度/取消监听，可空
     * @return 下载结果
     * @throws com.terrascout.orchestrator.core.error.TerraScoutException
     *         502001/502003/507001（下载）/ 422009（校验失败）
     */
    public DownloadResult download(String url, String expectedSha256, Path targetFile,
                                   Path diskCheckRoot, ProgressListener listener) {
        DownloadResult result = resumeable.download(url, targetFile, diskCheckRoot, listener);
        Sha256Verifier.verify(targetFile, expectedSha256);
        return result;
    }

    /**
     * 下载并校验（无监听）。
     */
    public DownloadResult download(String url, String expectedSha256, Path targetFile, Path diskCheckRoot) {
        return download(url, expectedSha256, targetFile, diskCheckRoot, null);
    }
}
