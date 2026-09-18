package com.terrascout.orchestrator.download;

/**
 * 单次下载结果（不可变）。totalBytes 与 bytesDownloaded 在内容长度可知时相等（整文件下载），
 * 未知长度（分块传输）时 bytesDownloaded 为实际写盘字节数。
 */
public record DownloadResult(long totalBytes, long bytesDownloaded, boolean usedResume) {
}
