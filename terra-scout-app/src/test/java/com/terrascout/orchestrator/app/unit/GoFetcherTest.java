package com.terrascout.orchestrator.app.unit;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.terrascout.orchestrator.app.service.catalog.CatalogHttpClient;
import com.terrascout.orchestrator.app.service.catalog.GoFetcher;
import com.terrascout.orchestrator.app.service.catalog.SdkCatalogEntry;
import com.terrascout.orchestrator.core.enums.ArchEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.OsTypeEnum;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Go 官方源抓取器单测（固定夹具 + 假客户端）：stable 过滤 / windows-amd64 归档匹配 /
 * 最新两个 minor 线策略（每线最新两版）/ 字段组装。
 */
class GoFetcherTest {

    private static final String SHA = "AB12CD34EF56AB78CD90EF12AB34CD56EF78AB90CD12EF34AB56CD78EF901234";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static String file(String filename, String os, String arch, String kind,
                               String sha, Long size) {
        return "{\"filename\":\"" + filename + "\",\"os\":\"" + os + "\",\"arch\":\"" + arch
                + "\",\"kind\":\"" + kind + "\",\"sha256\":\"" + sha + "\",\"size\":" + size + "}";
    }

    private static String release(String version, boolean stable, String... files) {
        return "{\"version\":\"" + version + "\",\"stable\":" + stable
                + ",\"files\":[" + String.join(",", files) + "]}";
    }

    private static String index(String... releases) {
        return "[" + String.join(",", releases) + "]";
    }

    @Test
    void keepsLatestTwoMinorLinesWithNewestTwoPatchesEach() throws Exception {
        String goodZip = file("go1.26.0.windows-amd64.zip", "windows", "amd64", "archive", SHA, 90000000L);
        String index = index(
                release("go1.23.0", false, file("go1.23.0.windows-amd64.zip", "windows", "amd64",
                        "archive", SHA, 9L)),
                release("go1.24.0", true, file("go1.24.0.linux-amd64.tar.gz", "linux", "amd64",
                        "archive", SHA, 2L)),
                release("go1.25.0", true, file("go1.25.0.windows-amd64.zip", "windows", "amd64",
                        "archive", SHA, 80000000L)),
                release("go1.25.1", true, file("go1.25.1.windows-amd64.zip", "windows", "amd64",
                        "archive", SHA, 81000000L)),
                release("go1.26.0", true,
                        file("go1.26.0.src.tar.gz", "any", "any", "source", SHA, 1L),
                        file("go1.26.0.windows-amd64.msi", "windows", "amd64", "archive", SHA, 4L),
                        file("go1.26.0.windows-amd64.zip", "windows", "amd64", "archive", "bad", 5L),
                        file("go1.26.0.windows-amd64.zip", "windows", "amd64", "archive", SHA, 90000000L)));
        CatalogHttpClient client = url -> index;

        List<SdkCatalogEntry> entries = new GoFetcher(client, objectMapper).fetch();

        // 仅最新两个 minor 线（1.26/1.25）；1.24 线在窗口外且无 windows 归档、1.23 非 stable；
        // 1.26 行 src/msi/坏 sha 全部跳过取合法 zip
        assertThat(entries).extracting(SdkCatalogEntry::getVersion)
                .containsExactly("1.26.0", "1.25.1", "1.25.0");
        SdkCatalogEntry first = entries.get(0);
        assertThat(first.getLanguage()).isEqualTo(LanguageEnum.GO);
        assertThat(first.getOs()).isEqualTo(OsTypeEnum.WINDOWS);
        assertThat(first.getArch()).isEqualTo(ArchEnum.AMD64);
        assertThat(first.getDownloadUrl()).isEqualTo("https://dl.google.com/go/go1.26.0.windows-amd64.zip");
        assertThat(first.getSha256()).isEqualTo(SHA.toLowerCase(Locale.ROOT));
        assertThat(first.getSizeBytes()).isEqualTo(90000000L);
        assertThat(first.getLicense()).isEqualTo("BSD-3-Clause");
        assertThat(first.getDistribution()).isEqualTo("Official");
        assertThat(first.getVendor()).isEqualTo("Google");
        assertThat(first.isLts()).isFalse();
        assertThat(first.isEol()).isFalse();
        assertThat(first.getReleaseTime()).isNull();
    }

    @Test
    void returnsEmptyWhenNoMatchingArchive() throws Exception {
        String index = index(
                release("go1.23.0", false, file("go1.23.0.windows-amd64.zip", "windows", "amd64",
                        "archive", SHA, 1L)),
                release("go1.24.0", true, file("go1.24.0.windows-amd64.zip", "windows",
                        "amd64", "archive", "not-a-sha", 2L)));
        CatalogHttpClient client = url -> index;
        assertThat(new GoFetcher(client, objectMapper).fetch()).isEmpty();
    }

    @Test
    void ignoresNonArrayResponse() throws Exception {
        CatalogHttpClient client = url -> "{\"stable\":true}";
        assertThat(new GoFetcher(client, objectMapper).fetch()).isEmpty();
    }

    @Test
    void keepsTwoNewestPatchesPerMinorLine() throws Exception {
        StringBuilder newestLine = new StringBuilder();
        for (int patch = 0; patch <= 3; patch++) {
            if (newestLine.length() > 0) {
                newestLine.append(',');
            }
            String version = "go1.26." + patch;
            newestLine.append(release(version, true,
                    file(version + ".windows-amd64.zip", "windows", "amd64", "archive", SHA, 100L)));
        }
        String older = release("go1.25.4", true,
                file("go1.25.4.windows-amd64.zip", "windows", "amd64", "archive", SHA, 100L));
        String legacy = release("go1.19.7", true,
                file("go1.19.7.windows-amd64.zip", "windows", "amd64", "archive", SHA, 100L));
        String index = index(older, legacy, newestLine.toString());
        CatalogHttpClient client = url -> index;

        List<SdkCatalogEntry> entries = new GoFetcher(client, objectMapper).fetch();
        // 仅 1.26/1.25 两条受支持线；1.26 线 4 个 patch 取最新两个；1.19 老线不在窗口
        assertThat(entries).extracting(SdkCatalogEntry::getVersion)
                .containsExactly("1.26.3", "1.26.2", "1.25.4");
    }

    @Test
    void attachesReleaseDatesFromGitHubTags() throws Exception {
        String index = index(release("go1.22.4", true,
                file("go1.22.4.windows-amd64.zip", "windows", "amd64", "archive", SHA, 76251174L)));
        String tags = "[{\"name\":\"go1.22.4\",\"commit\":{\"sha\":\"sha-1224\"}},"
                + "{\"name\":\"weekly.2012-03-27\",\"commit\":{\"sha\":\"sha-x\"}}]";
        CatalogHttpClient client = url -> {
            if (url.contains("/tags?")) {
                return tags;
            }
            if (url.contains("/commits/")) {
                return "{\"commit\":{\"committer\":{\"date\":\"2024-06-04T20:00:00Z\"}}}";
            }
            return index;
        };

        List<SdkCatalogEntry> entries = new GoFetcher(client, objectMapper).fetch();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getReleaseTime())
                .isEqualTo(Instant.parse("2024-06-04T20:00:00Z").toEpochMilli());
    }

    @Test
    void degradesWhenTagsApiUnavailable() throws Exception {
        String index = index(release("go1.22.4", true,
                file("go1.22.4.windows-amd64.zip", "windows", "amd64", "archive", SHA, 100L)));
        CatalogHttpClient client = url -> {
            if (url.contains("/tags?")) {
                throw new IOException("github api unavailable");
            }
            return index;
        };

        List<SdkCatalogEntry> entries = new GoFetcher(client, objectMapper).fetch();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getReleaseTime()).isNull();
    }

    @Test
    void fallsBackToMirrorIndexWhenOfficialUnavailable() throws Exception {
        String index = index(release("go1.22.4", true,
                file("go1.22.4.windows-amd64.zip", "windows", "amd64", "archive", SHA, 100L)));
        CatalogHttpClient client = url -> {
            if (url.startsWith("https://go.dev/dl/")) {
                throw new IOException("go.dev connect timed out");
            }
            if (url.contains("/tags?")) {
                return "[]";
            }
            return index; // 镜像索引与其余地址
        };

        List<SdkCatalogEntry> entries = new GoFetcher(client, objectMapper).fetch();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getVersion()).isEqualTo("1.22.4");
        assertThat(entries.get(0).getDownloadUrl())
                .isEqualTo("https://dl.google.com/go/go1.22.4.windows-amd64.zip");
        assertThat(entries.get(0).getReleaseTime()).isNull();
    }

    @Test
    void throwsWhenBothIndexesUnavailable() {
        CatalogHttpClient client = url -> {
            throw new IOException("both indexes unreachable");
        };
        assertThatThrownBy(() -> new GoFetcher(client, objectMapper).fetch())
                .isInstanceOf(IOException.class)
                .hasMessage("both indexes unreachable");
    }
}
