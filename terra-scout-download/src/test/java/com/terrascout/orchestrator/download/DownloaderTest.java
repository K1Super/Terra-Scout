package com.terrascout.orchestrator.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

/**
 * {@link Downloader} 编排测试：下载 + SHA-256 校验（正常 / 损坏 422009 / 源不可达 502001）。
 */
class DownloaderTest {

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
    void downloadAndVerifySucceeds() throws IOException {
        byte[] content = "hello world\n".getBytes(StandardCharsets.UTF_8);
        Path expect = workDir.resolve("expect");
        Files.write(expect, content);
        String sha = Sha256Verifier.sha256(expect);
        server.enqueue(new MockResponse().setResponseCode(200).setBody(asBuffer(content)));

        Path target = workDir.resolve("sdk.bin");
        DownloadResult result = downloader.download(url(), sha, target, workDir);

        assertThat(result.bytesDownloaded()).isEqualTo(content.length);
        assertThat(Files.readAllBytes(target)).isEqualTo(content);
        assertThat(Sha256Verifier.matches(target, sha)).isTrue();
    }

    @Test
    void tamperedBodyThrowsCheckSumMismatch() throws IOException {
        byte[] good = "good content\n".getBytes(StandardCharsets.UTF_8);
        Path expect = workDir.resolve("expect");
        Files.write(expect, good);
        String sha = Sha256Verifier.sha256(expect);
        // 服务端返回篡改内容。
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody(asBuffer("tampered body!\n".getBytes(StandardCharsets.UTF_8))));

        assertThatThrownBy(() -> downloader.download(url(), sha, workDir.resolve("sdk.bin"), workDir))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> {
                    TerraScoutException ex = (TerraScoutException) e;
                    assertThat(ex.getError()).isEqualTo(TerraScoutError.CHECKSUM_MISMATCH);
                });
    }

    @Test
    void unreachableSourceThrows502001() throws IOException {
        Path placeholder = workDir.resolve("placeholder");
        Files.write(placeholder, new byte[]{});
        assertThatThrownBy(() -> downloader.download("http://127.0.0.1:1/nope",
                Sha256Verifier.sha256(placeholder), workDir.resolve("sdk.bin"), workDir))
                .isInstanceOf(TerraScoutException.class)
                .extracting(e -> ((TerraScoutException) e).getError())
                .isEqualTo(TerraScoutError.SDK_SOURCE_UNREACHABLE);
    }

    private String url() {
        return server.url("/sdk").toString();
    }

    private static Buffer asBuffer(byte[] bytes) {
        return new Buffer().write(bytes);
    }
}
