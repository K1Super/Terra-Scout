package com.terrascout.orchestrator.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link DownloadTask} 测试：可取消、进度上报、结果缓存。
 */
class DownloadTaskTest {

    @TempDir
    Path workDir;

    private MockWebServer server;
    private Downloader downloader;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        downloader = new Downloader(new ResumeableDownloader(new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void runDownloadsTracksProgressAndCachesResult() throws IOException {
        byte[] content = new byte[1000];
        Arrays.fill(content, (byte) 5);
        Path expect = Files.write(workDir.resolve("expect"), content);
        String sha = Sha256Verifier.sha256(expect);
        server.enqueue(new MockResponse().setResponseCode(200).setBody(asBuffer(content)));

        DownloadTask task = new DownloadTask(url(), sha, workDir.resolve("out.bin"), workDir, downloader);
        DownloadResult result = task.run();

        assertThat(result.bytesDownloaded()).isEqualTo(content.length);
        assertThat(task.downloadedBytes()).isEqualTo(content.length);
        assertThat(task.result()).isSameAs(result);
        assertThat(Files.readAllBytes(workDir.resolve("out.bin"))).isEqualTo(content);
    }

    @Test
    void cancelBeforeRunAbortsAndKeepsPart() throws IOException {
        byte[] big = new byte[20_000];
        Arrays.fill(big, (byte) 1);
        server.enqueue(new MockResponse().setResponseCode(200).setBody(asBuffer(big)));

        Files.write(workDir.resolve("placeholder"), new byte[]{});
        DownloadTask task = new DownloadTask(url(), Sha256Verifier.sha256(workDir.resolve("placeholder")),
                workDir.resolve("out.bin"), workDir, downloader);
        task.cancel();

        assertThatThrownBy(task::run)
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        // 目标未生成；.part 已创建（预取消，尚无任何分片，0 字节）。
        assertThat(workDir.resolve("out.bin")).doesNotExist();
        assertThat(Files.exists(workDir.resolve("out.bin.part"))).isTrue();
    }

    private String url() {
        return server.url("/sdk").toString();
    }

    private static Buffer asBuffer(byte[] bytes) {
        return new Buffer().write(bytes);
    }
}
