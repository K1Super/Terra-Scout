package com.terrascout.orchestrator.app.unit;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.terrascout.orchestrator.app.service.catalog.CatalogHttpClient;
import com.terrascout.orchestrator.app.service.catalog.PythonFetcher;
import com.terrascout.orchestrator.app.service.catalog.SdkCatalogEntry;
import com.terrascout.orchestrator.core.enums.ArchEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.OsTypeEnum;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Python（CPython）官方源抓取器单测（固定夹具 + 假客户端 + 固定时钟）：稳定目录正则 /
 * windows 元数据挑选（排除 embed）/ 行级 EOL 裁决 / 每线最多两版本策略。
 */
class PythonFetcherTest {

    /** python-3.12.10-amd64.zip 官方 sha256（取自 python.org windows-3.12.10.json）。 */
    private static final String SHA = "8649692DE846C56A7189D6DAE5C322AB20DEB1B5908B6F39426B62A36F39415D";

    /** 固定「今天」：3.10 仍在支持期（EOL 2026-10-31），3.9 已 EOL（2025-10-31）。 */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-18T00:00:00Z"), ZoneOffset.UTC);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static String sha64(String seed) {
        return String.format("%064x", Math.abs(seed.hashCode()));
    }

    private static String indexHtml(String... versions) {
        StringBuilder html = new StringBuilder("<html><body>");
        for (String version : versions) {
            html.append("<a href=\"").append(version).append("/\">").append(version).append("</a>");
        }
        return html.append("</body></html>").toString();
    }

    private static String pyFile(String url, String sha) {
        return "{\"url\":\"" + url + "\",\"hash\":{\"sha256\":\"" + sha + "\"}}";
    }

    private static String windowsBody(String... files) {
        return "{\"versions\":[" + String.join(",", files) + "]}";
    }

    /** 官方 downloads API 夹具（现行全量数组形态）：偶数下标参数为 (version, yyyy-MM-dd) 对。 */
    private static String datesBody(String... versionDatePairs) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < versionDatePairs.length; i += 2) {
            if (items.length() > 0) {
                items.append(',');
            }
            items.append("{\"name\":\"").append(versionDatePairs[i])
                    .append("\",\"release_date\":\"").append(versionDatePairs[i + 1])
                    .append("\",\"pre_release\":false}");
        }
        return "[" + items + "]";
    }

    /** 日期 API 匹配：凡含 downloads/release 的地址返回日期夹具。 */
    private static boolean isDatesApi(String url) {
        return url.contains("downloads/release");
    }

    private static String goodUrl(String version) {
        return "https://www.python.org/ftp/python/" + version + "/python-" + version + "-amd64.zip";
    }

    @Test
    void parsesStableDirectoriesAndBuildsEntries() throws Exception {
        String index = indexHtml("3.14.1", "3.14.0", "3.13.1", "3.13.0a1", "3.12.10", "3.12.9",
                "3.11.9", "3.9.7", "2.7.18");
        AtomicInteger probes = new AtomicInteger();
        CatalogHttpClient client = url -> {
            if (url.equals(PythonFetcher.INDEX_URL)) {
                return index;
            }
            if (isDatesApi(url)) {
                return datesBody("3.14.1", "2025-12-02", "3.14.0", "2025-10-07",
                        "3.13.1", "2024-12-03", "3.12.10", "2024-12-04",
                        "3.12.9", "2024-12-03", "3.11.9", "2024-12-03");
            }
            if (url.contains("windows-")) {
                probes.incrementAndGet();
            }
            if (url.contains("3.14.1")) {
                return windowsBody(pyFile(goodUrl("3.14.1"), sha64("3.14.1")));
            }
            if (url.contains("3.14.0")) {
                return windowsBody(pyFile(goodUrl("3.14.0"), sha64("3.14.0")));
            }
            if (url.contains("3.13.1")) {
                return windowsBody(pyFile(goodUrl("3.13.1"), sha64("3.13.1")));
            }
            if (url.contains("3.12.10")) {
                return windowsBody(
                        pyFile("https://www.python.org/ftp/python/3.12.10/python-3.12.10-embed-amd64.zip",
                                sha64("embed")),
                        pyFile(goodUrl("3.12.10"), SHA));
            }
            if (url.contains("3.12.9")) {
                return windowsBody(pyFile(goodUrl("3.12.9"), sha64("3.12.9")));
            }
            return windowsBody(pyFile(goodUrl("3.11.9"), sha64("3.11.9")));
        };

        List<SdkCatalogEntry> entries = new PythonFetcher(client, objectMapper, CLOCK).fetch();

        // 受支持线倒序、每线最新两个 patch；3.13.0a1 被稳定目录正则排除；
        // 3.9（EOL 2025-10-31）/ 2.7 旧线整体排除
        assertThat(entries).extracting(SdkCatalogEntry::getVersion)
                .containsExactly("3.14.1", "3.14.0", "3.13.1", "3.12.10", "3.12.9", "3.11.9");
        // 排除线（3.9.x / 2.7.x）从未被探测：8 个稳定目录仅 6 个受支持版触发 windows 探测
        assertThat(probes.get()).isEqualTo(6);
        SdkCatalogEntry third = entries.get(3);
        assertThat(third.getDownloadUrl()).isEqualTo(goodUrl("3.12.10"));
        assertThat(third.getSha256()).isEqualTo(SHA.toLowerCase(Locale.ROOT));
        assertThat(third.getLanguage()).isEqualTo(LanguageEnum.PYTHON);
        assertThat(third.getOs()).isEqualTo(OsTypeEnum.WINDOWS);
        assertThat(third.getArch()).isEqualTo(ArchEnum.AMD64);
        assertThat(third.getLicense()).isEqualTo("PSF-2.0");
        assertThat(third.getDistribution()).isEqualTo("CPython");
        assertThat(third.getVendor()).isEqualTo("Python Software Foundation");
        assertThat(third.isLts()).isFalse();
        assertThat(third.isEol()).isFalse();
        // 发布日期来自官方 downloads API（UTC 零点）
        assertThat(third.getReleaseTime()).isEqualTo(
                LocalDate.parse("2024-12-04").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
    }

    @Test
    void degradesToNullReleaseTimeWhenDatesApiUnavailable() throws Exception {
        String index = indexHtml("3.12.10");
        CatalogHttpClient client = url -> {
            if (url.equals(PythonFetcher.INDEX_URL)) {
                return index;
            }
            if (isDatesApi(url)) {
                throw new IOException("dates api down");
            }
            return windowsBody(pyFile(goodUrl("3.12.10"), sha64("3.12.10")));
        };

        List<SdkCatalogEntry> entries = new PythonFetcher(client, objectMapper, CLOCK).fetch();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getReleaseTime()).isNull();
    }

    @Test
    void skipsPreReleaseEntriesSoFinalReleaseDateWins() throws Exception {
        String index = indexHtml("3.14.0");
        // 真实 API 为发布升序：alpha 条目先于正式版出现，未过滤会抢占正式版日期
        String dates = "["
                + "{\"name\":\"Python 3.14.0a1\",\"release_date\":\"2024-10-15\",\"pre_release\":true},"
                + "{\"name\":\"Python 3.14.0\",\"release_date\":\"2025-10-07\",\"pre_release\":false}]";
        CatalogHttpClient client = url -> {
            if (url.equals(PythonFetcher.INDEX_URL)) {
                return index;
            }
            if (isDatesApi(url)) {
                return dates;
            }
            return windowsBody(pyFile(goodUrl("3.14.0"), sha64("3.14.0")));
        };

        List<SdkCatalogEntry> entries = new PythonFetcher(client, objectMapper, CLOCK).fetch();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getReleaseTime()).isEqualTo(
                LocalDate.parse("2025-10-07").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
    }

    @Test
    void resolvesDatesFromPagedFallbackShape() throws Exception {
        String index = indexHtml("3.14.0", "3.13.1");
        String pageOne = "{\"results\":["
                + "{\"name\":\"Python 3.14.0\",\"release_date\":\"2025-10-07\",\"pre_release\":false}],"
                + "\"next\":\"https://www.python.org/api/v2/downloads/release/?page=2&page_size=50\"}";
        String pageTwo = "{\"results\":["
                + "{\"name\":\"Python 3.13.1\",\"release_date\":\"2024-12-03\",\"pre_release\":false}],"
                + "\"next\":null}";
        CatalogHttpClient client = url -> {
            if (url.equals(PythonFetcher.INDEX_URL)) {
                return index;
            }
            if (url.contains("page=2")) {
                return pageTwo;
            }
            if (isDatesApi(url)) {
                return pageOne;
            }
            if (url.contains("3.14.0")) {
                return windowsBody(pyFile(goodUrl("3.14.0"), sha64("3.14.0")));
            }
            return windowsBody(pyFile(goodUrl("3.13.1"), sha64("3.13.1")));
        };

        List<SdkCatalogEntry> entries = new PythonFetcher(client, objectMapper, CLOCK).fetch();
        assertThat(entries).extracting(SdkCatalogEntry::getVersion)
                .containsExactly("3.14.0", "3.13.1");
        assertThat(entries.get(0).getReleaseTime()).isEqualTo(
                LocalDate.parse("2025-10-07").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
        assertThat(entries.get(1).getReleaseTime()).isEqualTo(
                LocalDate.parse("2024-12-03").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
    }

    @Test
    void skipsVersionsWithoutWindowsJson() throws Exception {
        String index = indexHtml("3.13.1", "3.12.10");
        CatalogHttpClient client = url -> {
            if (url.equals(PythonFetcher.INDEX_URL)) {
                return index;
            }
            if (url.contains("3.13.1")) {
                throw new IOException("404");
            }
            return windowsBody(pyFile(goodUrl("3.12.10"), sha64("3.12.10")));
        };

        List<SdkCatalogEntry> entries = new PythonFetcher(client, objectMapper, CLOCK).fetch();
        assertThat(entries).extracting(SdkCatalogEntry::getVersion).containsExactly("3.12.10");
    }

    @Test
    void skipsEmbedNonZipAndBadSha() throws Exception {
        String index = indexHtml("3.12.10");
        CatalogHttpClient client = url -> {
            if (url.equals(PythonFetcher.INDEX_URL)) {
                return index;
            }
            return windowsBody(
                    pyFile("https://www.python.org/ftp/python/3.12.10/python-3.12.10-embed-amd64.zip",
                            sha64("embed")),
                    pyFile("https://www.python.org/ftp/python/3.12.10/python-3.12.10.exe", sha64("exe")),
                    pyFile(goodUrl("3.12.10"), "bad"),
                    pyFile(goodUrl("3.12.10"), sha64("3.12.10")));
        };

        List<SdkCatalogEntry> entries = new PythonFetcher(client, objectMapper, CLOCK).fetch();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getSha256()).isEqualTo(sha64("3.12.10"));
    }

    @Test
    void keepsTwoNewestPatchesPerLine() throws Exception {
        String index = indexHtml("3.12.10", "3.12.9", "3.12.8", "3.12.7");
        AtomicInteger probes = new AtomicInteger();
        CatalogHttpClient client = url -> {
            if (url.equals(PythonFetcher.INDEX_URL)) {
                return index;
            }
            if (url.contains("windows-")) {
                probes.incrementAndGet();
            }
            for (String candidate : List.of("3.12.10", "3.12.9", "3.12.8", "3.12.7")) {
                if (url.contains(candidate)) {
                    return windowsBody(pyFile(goodUrl(candidate), sha64(candidate)));
                }
            }
            throw new IOException("unexpected url: " + url);
        };

        List<SdkCatalogEntry> entries = new PythonFetcher(client, objectMapper, CLOCK).fetch();
        assertThat(entries).extracting(SdkCatalogEntry::getVersion)
                .containsExactly("3.12.10", "3.12.9");
        // 收满两条即停，更旧 patch（3.12.8/3.12.7）不再探测
        assertThat(probes.get()).isEqualTo(PythonFetcher.KEEP_PER_LINE);
    }

    @Test
    void capsProbesPerLineAtLimit() throws Exception {
        // 20 个候选全部 404：单线至多探测 PROBE_PER_LINE 次即止损（防无限深挖）
        List<String> versions = new ArrayList<>();
        for (int patch = 20; patch >= 1; patch--) {
            versions.add("3.14." + patch);
        }
        String index = indexHtml(versions.toArray(new String[0]));
        AtomicInteger probes = new AtomicInteger();
        CatalogHttpClient client = url -> {
            if (url.equals(PythonFetcher.INDEX_URL)) {
                return index;
            }
            if (url.contains("windows-")) {
                probes.incrementAndGet();
            }
            throw new IOException("404");
        };

        List<SdkCatalogEntry> entries = new PythonFetcher(client, objectMapper, CLOCK).fetch();
        assertThat(entries).isEmpty();
        // 单线 windows 元数据最多探测 PROBE_PER_LINE 次（20 个候选时第 17 个起不探）
        assertThat(probes.get()).isEqualTo(PythonFetcher.PROBE_PER_LINE);
    }

    @Test
    void probesPastSourceOnlyPatchesUntilTwoInstallablePerLine() throws Exception {
        // 3.11 线：3.11.10~3.11.16 为安全维护期「仅源码」发布（windows 元数据 404），
        // 直到 3.11.9/3.11.8 才有 Windows 产物 → 顺延深挖取该线最近可安装的两个 patch
        String index = indexHtml("3.11.16", "3.11.15", "3.11.14", "3.11.13", "3.11.12",
                "3.11.11", "3.11.10", "3.11.9", "3.11.8");
        AtomicInteger probes = new AtomicInteger();
        CatalogHttpClient client = url -> {
            if (url.equals(PythonFetcher.INDEX_URL)) {
                return index;
            }
            if (url.contains("windows-")) {
                probes.incrementAndGet();
            }
            if (url.contains("3.11.9")) {
                return windowsBody(pyFile(goodUrl("3.11.9"), sha64("3.11.9")));
            }
            if (url.contains("3.11.8")) {
                return windowsBody(pyFile(goodUrl("3.11.8"), sha64("3.11.8")));
            }
            throw new IOException("404");
        };

        List<SdkCatalogEntry> entries = new PythonFetcher(client, objectMapper, CLOCK).fetch();
        assertThat(entries).extracting(SdkCatalogEntry::getVersion)
                .containsExactly("3.11.9", "3.11.8");
        // 跳过 7 个仅源码发布后取到该线最近可安装的两个 patch（收满即停）
        assertThat(probes.get()).isEqualTo(9);
    }

    @Test
    void excludesLinesAtOrPastEolAndProbesOnlySupportedLines() throws Exception {
        String index = indexHtml("3.14.0", "3.10.15", "3.9.7", "3.8.20", "2.7.18");
        AtomicInteger probes = new AtomicInteger();
        CatalogHttpClient client = url -> {
            if (url.equals(PythonFetcher.INDEX_URL)) {
                return index;
            }
            if (url.contains("windows-")) {
                probes.incrementAndGet();
            }
            if (url.contains("3.14.0")) {
                return windowsBody(pyFile(goodUrl("3.14.0"), sha64("3.14.0")));
            }
            return windowsBody(pyFile(goodUrl("3.10.15"), sha64("3.10.15")));
        };

        List<SdkCatalogEntry> entries = new PythonFetcher(client, objectMapper, CLOCK).fetch();
        // 3.10 EOL 2026-10-31，今天（固定 2026-09-18）仍在支持期 → 纳入；
        // 3.9（EOL 2025-10-31）/ 3.8 / 2.7 旧线排除且从未探测
        assertThat(entries).extracting(SdkCatalogEntry::getVersion)
                .containsExactly("3.14.0", "3.10.15");
        assertThat(probes.get()).isEqualTo(2);
    }

    @Test
    void includesFutureLineBeyondTableAndExcludesLegacyLines() throws Exception {
        String index = indexHtml("3.15.0", "3.15.1", "3.3.7", "2.7.18");
        AtomicInteger probes = new AtomicInteger();
        CatalogHttpClient client = url -> {
            if (url.equals(PythonFetcher.INDEX_URL)) {
                return index;
            }
            if (url.contains("windows-")) {
                probes.incrementAndGet();
            }
            if (url.contains("3.15.1")) {
                return windowsBody(pyFile(goodUrl("3.15.1"), sha64("3.15.1")));
            }
            return windowsBody(pyFile(goodUrl("3.15.0"), sha64("3.15.0")));
        };

        List<SdkCatalogEntry> entries = new PythonFetcher(client, objectMapper, CLOCK).fetch();
        // 3.15 表外新于表内最高行（3.14）→ 未来受支持行；3.3 / 2.7 旧线排除
        assertThat(entries).extracting(SdkCatalogEntry::getVersion)
                .containsExactly("3.15.1", "3.15.0");
        assertThat(probes.get()).isEqualTo(2);
    }
}
