package com.terrascout.orchestrator.download;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CancellationException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

/**
 * 断点续传下载器（security.md 6.6：Range + ETag / .part 临时文件 / 磁盘≥2 倍文件校验）。
 *
 * <ul>
 *   <li>续传：向服务端发送 {@code Range: bytes={existing}-}，成功返回 206 时在 .part 后追加；</li>
 *   <li>若请求了 Range 而服务端回 200 覆盖全文件 → 502003 RANGE_UNSUPPORTED；</li>
 *   <li>连接失败 / 非 2xx → 502001 SDK_SOURCE_UNREACHABLE；</li>
 *   <li>下载目录可用空间 &lt; 2×目标大小 → 507001 DOWNLOAD_DISK_FULL；</li>
 *   <li>失败/取消仅保留 .part，不删除（供后续续传）；成功后将 .part 原子移动为目标文件。</li>
 * </ul>
 */
public final class ResumeableDownloader {

    /** 未完成下载的临时文件后缀（security.md 6.6）。 */
    public static final String PART_SUFFIX = ".part";

    /** 磁盘预检系数：可用空间需 ≥ 2 倍目标大小（security.md 6.6）。 */
    private static final long DISK_HEADROOM = 2L;

    private static final int BUFFER_SIZE = 8192;

    private final OkHttpClient client;

    /** 使用默认超时客户端。 */
    public ResumeableDownloader() {
        this(new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .build());
    }

    /**
     * 注入 HTTP 客户端（测试注入短超时 / MockWebServer 指向）。
     *
     * @param client OkHttp 客户端
     */
    public ResumeableDownloader(OkHttpClient client) {
        this.client = java.util.Objects.requireNonNull(client, "client 不能为 null");
    }

    /**
     * 下载 URL 到 targetFile（经 .part 续传），写入成功前不产生 targetFile。
     *
     * @param url          下载地址
     * @param targetFile   目标文件（成功后生成）
     * @param diskCheckRoot 磁盘空间检查的根目录（通常为目标所在目录）
     * @param listener     进度/取消监听，可空
     * @return 下载结果
     * @throws TerraScoutException 502001 源不可达；502003 服务端不支持 Range；507001 磁盘不足
     * @throws CancellationException 监听方请求取消（保留 .part）
     */
    public DownloadResult download(String url, Path targetFile, Path diskCheckRoot, ProgressListener listener) {
        java.util.Objects.requireNonNull(url, "url 不能为 null");
        java.util.Objects.requireNonNull(targetFile, "targetFile 不能为 null");
        java.util.Objects.requireNonNull(diskCheckRoot, "diskCheckRoot 不能为 null");

        Path part = partOf(targetFile);
        long resumeAt = readResumeOffset(part);
        Request request = buildRequest(url, resumeAt);
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new TerraScoutException(TerraScoutError.SDK_SOURCE_UNREACHABLE,
                        "下载源返回非成功状态: HTTP " + response.code() + " " + url);
            }
            if (isResumeRequest(resumeAt)) {
                // 306 与 200 语义：请求了 Range 却返回 200 全量 → 服务端不支持续传。
                if (response.code() == 200) {
                    throw new TerraScoutException(TerraScoutError.RANGE_UNSUPPORTED,
                            "服务端忽略 Range，不支持断点续传: " + url);
                }
            }
            ResponseBody body = response.body();
            long contentLength = body == null ? 0L : body.contentLength();
            long total = resumeAt + Math.max(contentLength, 0L);
            checkDiskSpace(diskCheckRoot, total);
            long done = streamToFile(response, body, part, resumeAt, total, listener);
            moveToFinal(part, targetFile);
            long written = resumeAt + done;
            return new DownloadResult(total > 0 ? total : written, written, isResumeRequest(resumeAt));
        } catch (IOException e) {
            throw new TerraScoutException(TerraScoutError.SDK_SOURCE_UNREACHABLE,
                    "连接下载源失败: " + url, e);
        }
    }

    /**
     * 下载 URL 到 targetFile（无监听）。
     */
    public DownloadResult download(String url, Path targetFile, Path diskCheckRoot) {
        return download(url, targetFile, diskCheckRoot, null);
    }

    // ---------------------------------------------------------------- 内部

    private static Path partOf(Path targetFile) {
        return targetFile.resolveSibling(targetFile.getFileName() + PART_SUFFIX);
    }

    private static long readResumeOffset(Path part) {
        if (Files.isRegularFile(part)) {
            try {
                return Files.size(part);
            } catch (IOException e) {
                throw new IllegalStateException("读取 .part 大小失败: " + part, e);
            }
        }
        return 0L;
    }

    private static boolean isResumeRequest(long resumeAt) {
        return resumeAt > 0L;
    }

    private static Request buildRequest(String url, long resumeAt) {
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(url).get();
        if (isResumeRequest(resumeAt)) {
            builder.header("Range", "bytes=" + resumeAt + "-");
        }
        return builder.build();
    }

    /**
     * 磁盘预检：可用空间 &lt; 2×目标大小抛 507001。
     */
    private static void checkDiskSpace(Path root, long total) {
        if (total <= 0L) {
            return;
        }
        long usable;
        try {
            usable = Files.getFileStore(root).getUsableSpace();
        } catch (IOException e) {
            throw new IllegalStateException("获取磁盘可用空间失败: " + root, e);
        }
        if (usable < total * DISK_HEADROOM) {
            throw new TerraScoutException(TerraScoutError.DOWNLOAD_DISK_FULL,
                    "下载目录磁盘空间不足: 需 " + total * DISK_HEADROOM + " 字节，可用 " + usable);
        }
    }

    /**
     * 将响应体写入 .part：续传（206）追加，首次（200/部分）截断写。
     *
     * @return 本次写入的字节数（不含既有分片）
     */
    private static long streamToFile(Response response, ResponseBody body, Path part,
                                      long resumeAt, long total, ProgressListener listener)
            throws IOException {
        if (body == null) {
            return 0L;
        }
        long written = 0L;
        boolean append = isResumeRequest(resumeAt);
        try (InputStream in = body.byteStream()) {
            if (append) {
                try (OutputStream out = Files.newOutputStream(part, StandardOpenOption.APPEND)) {
                    written = copyWithListener(in, out, resumeAt, total, listener);
                }
            } else {
                try (OutputStream out = Files.newOutputStream(part,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    written = copyWithListener(in, out, 0L, total, listener);
                }
            }
        }
        return written;
    }

    private static long copyWithListener(InputStream in, OutputStream out,
                                         long offset, long total, ProgressListener listener)
            throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long done = offset;
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (listener != null && listener.isCancelled()) {
                throw new CancellationException("下载已取消，保留 .part 供续传");
            }
            out.write(buffer, 0, read);
            done += read;
            if (listener != null) {
                listener.onProgress(total, done);
            }
        }
        return done - offset;
    }

    private static void moveToFinal(Path part, Path targetFile) throws IOException {
        try {
            Files.move(part, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(part, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
