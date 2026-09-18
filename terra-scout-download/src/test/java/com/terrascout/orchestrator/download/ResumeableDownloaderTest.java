package com.terrascout.orchestrator.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

/**
 * {@link ResumeableDownloader} MockWebServer 测试（test-data 9.3：正常/续传/不支持 Range/源不可达/取消）。
 * 禁止直连公网。
 */
class ResumeableDownloaderTest {

    private static final byte[] FULL_100 = range(100);

    @TempDir
    Path workDir;

    private MockWebServer server;
    private ResumeableDownloader downloader;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        downloader = new ResumeableDownloader(new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void full200DownloadSucceeds() throws Exception {
        Path target = workDir.resolve("out.bin");
        server.enqueue(new MockResponse().setResponseCode(200).setBody(asBuffer(FULL_100)));

        DownloadResult result = downloader.download(url(), target, workDir);

        assertThat(Files.readAllBytes(target)).isEqualTo(FULL_100);
        assertThat(result.totalBytes()).isEqualTo(100);
        assertThat(result.bytesDownloaded()).isEqualTo(100);
        assertThat(result.usedResume()).isFalse();
        assertThat(workDir.resolve("out.bin.part")).doesNotExist();
    }

    @Test
    void resumeFromPartUsesRangeAndAppends() throws Exception {
        Path target = workDir.resolve("out.bin");
        byte[] first50 = Arrays.copyOfRange(FULL_100, 0, 50);
        byte[] last50 = Arrays.copyOfRange(FULL_100, 50, 100);
        Files.write(workDir.resolve("out.bin.part"), first50);

        server.enqueue(new MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 50-99/100")
                .setHeader("Content-Length", "50")
                .setBody(asBuffer(last50)));

        DownloadResult result = downloader.download(url(), target, workDir);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("Range")).isEqualTo("bytes=50-");
        assertThat(result.usedResume()).isTrue();
        assertThat(Files.readAllBytes(target)).isEqualTo(FULL_100);
    }

    @Test
    void serverIgnoringRangeYields502003() throws IOException {
        Path target = workDir.resolve("out.bin");
        Files.write(workDir.resolve("out.bin.part"), Arrays.copyOfRange(FULL_100, 0, 50));
        server.enqueue(new MockResponse().setResponseCode(200).setBody(asBuffer(FULL_100)));

        assertThatThrownBy(() -> downloader.download(url(), target, workDir))
                .isInstanceOf(TerraScoutException.class)
                .extracting(e -> ((TerraScoutException) e).getError())
                .isEqualTo(TerraScoutError.RANGE_UNSUPPORTED);
    }

    @Test
    void non2xxYields502001() {
        Path target = workDir.resolve("out.bin");
        server.enqueue(new MockResponse().setResponseCode(404).setBody("nope"));

        assertThatThrownBy(() -> downloader.download(url(), target, workDir))
                .isInstanceOf(TerraScoutException.class)
                .extracting(e -> ((TerraScoutException) e).getError())
                .isEqualTo(TerraScoutError.SDK_SOURCE_UNREACHABLE);
    }

    @Test
    void connectionRefusedYields502001() {
        Path target = workDir.resolve("out.bin");
        // 已关闭端口 → 连接被拒绝。
        assertThatThrownBy(() -> downloader.download("http://127.0.0.1:1/nope", target, workDir))
                .isInstanceOf(TerraScoutException.class)
                .extracting(e -> ((TerraScoutException) e).getError())
                .isEqualTo(TerraScoutError.SDK_SOURCE_UNREACHABLE);
    }

    @Test
    void cancelLeavesPartForResume() throws IOException {
        Path target = workDir.resolve("out.bin");
        byte[] big = new byte[20_000];
        Arrays.fill(big, (byte) 1);
        server.enqueue(new MockResponse().setResponseCode(200).setBody(asBuffer(big)));

        AtomicBoolean cancelled = new AtomicBoolean(false);
        ProgressListener listener = new ProgressListener() {
            @Override
            public void onProgress(long totalBytes, long bytesDownloaded) {
                cancelled.set(true);
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };

        assertThatThrownBy(() -> downloader.download(url(), target, workDir, listener))
                .isInstanceOf(CancellationException.class);

        // 目标未生成，但 .part 保留了已完成分片（数量级相同即可，>0 且 < 总量）。
        assertThat(target).doesNotExist();
        Path part = workDir.resolve("out.bin.part");
        assertThat(part).exists();
        long partBytes = Files.size(part);
        assertThat(partBytes).isGreaterThan(0).isLessThan(big.length);
    }

    private String url() {
        return server.url("/file").toString();
    }

    private static byte[] range(int count) {
        byte[] result = new byte[count];
        for (int i = 0; i < count; i++) {
            result[i] = (byte) i;
        }
        return result;
    }

    private static Buffer asBuffer(byte[] bytes) {
        return new Buffer().write(bytes);
    }
}
