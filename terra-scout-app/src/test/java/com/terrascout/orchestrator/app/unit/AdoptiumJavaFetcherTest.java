package com.terrascout.orchestrator.app.unit;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.terrascout.orchestrator.app.service.catalog.AdoptiumJavaFetcher;
import com.terrascout.orchestrator.app.service.catalog.CatalogHttpClient;
import com.terrascout.orchestrator.app.service.catalog.SdkCatalogEntry;
import com.terrascout.orchestrator.core.enums.ArchEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.OsTypeEnum;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adoptium（Eclipse Temurin）JAVA 官方源解析单测（固定夹具 + 假客户端）。
 */
class AdoptiumJavaFetcherTest {

    /** LTS 列表含过老的 8（应被 MIN_MAJOR 过滤），余下 11/17/21/25 + feature 26 构成 5 候选。 */
    private static final String INFO_JSON =
            "{\"available_lts_releases\":[8,11,17,21,25],"
                    + "\"most_recent_feature_release\":26,\"most_recent_lts\":25,\"tip_version\":27}";

    /** 仅单一候选 major 的镜像（用于只关心资产过滤、不关心候选集合的用例）。 */
    private static final String SINGLE_LTS_INFO_JSON =
            "{\"available_lts_releases\":[21],\"most_recent_feature_release\":21}";

    private static final String SHA = "F9D6E191AB098C0D416E7D588A24420A8621CD2F4720DAB2459B8B7B2D2D8B4E";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 单个 release 资产（对象粒度，无外层数组；组装处统一包数组）。 */
    private static String asset(String version, String link, String os, String imageType, String checksum) {
        return "{\"binaries\":[{\"os\":\"" + os + "\",\"architecture\":\"x64\",\"image_type\":\"" + imageType
                + "\",\"package\":{\"link\":\"" + link + "\",\"checksum\":\"" + checksum
                + "\",\"size\":1000}}],\"timestamp\":\"2026-08-19T13:28:29Z\",\"vendor\":\"eclipse\","
                + "\"version_data\":{\"openjdk_version\":\"" + version + "\",\"major\":21}}";
    }

    @Test
    void fetchesLastFiveLtsPlusFeatureRelease() throws Exception {
        CatalogHttpClient client = url -> route(url, Map.of(
                11, "11.0.30+9", 17, "17.0.20.1+1", 21, "21.0.12.1+1", 25, "25.0.1+8", 26, "26.0.2+9"));
        List<SdkCatalogEntry> entries = new AdoptiumJavaFetcher(client, objectMapper).fetch();

        // 8 被 MIN_MAJOR 淘汰：LTS 后 5 个 {11,17,21,25} + feature 26 → 5 条
        assertThat(entries).hasSize(5);
        assertThat(entries).extracting(SdkCatalogEntry::getVersion)
                .containsExactly("11.0.30", "17.0.20.1", "21.0.12.1", "25.0.1", "26.0.2");
        SdkCatalogEntry lts = entries.get(3);
        assertThat(lts.isLts()).isTrue();
        assertThat(lts.getSha256()).isEqualTo(SHA.toLowerCase(Locale.ROOT));
        assertThat(lts.getSizeBytes()).isEqualTo(1000L);
        assertThat(lts.getLanguage()).isEqualTo(LanguageEnum.JAVA);
        assertThat(lts.getOs()).isEqualTo(OsTypeEnum.WINDOWS);
        assertThat(lts.getArch()).isEqualTo(ArchEnum.AMD64);
        assertThat(lts.getLicense()).isEqualTo("GPL-2.0-with-classpath-exception");
        assertThat(lts.getDistribution()).isEqualTo("Temurin");
        assertThat(lts.getVendor()).isEqualTo("Eclipse Adoptium");
        assertThat(lts.isEol()).isFalse();
        assertThat(lts.getReleaseTime()).isNotNull();
    }

    @Test
    void skipsJreNonZipNonWhitelistedAndMissingFields() throws Exception {
        String withJreOnly = asset("21.0.12.1+1", "https://x.invalid/jre.zip", "windows", "jre", SHA);
        String withNonHttps = asset("21.0.12.1+1", "http://api.adoptium.net/pkg.zip", "windows", "jdk", SHA);
        String withBadChecksum = asset("21.0.12.1+1",
                "https://github.com/adoptium/pkg.zip", "windows", "jdk", "abc");
        String withoutVersion = "[{\"binaries\":[{\"os\":\"windows\",\"architecture\":\"x64\",\"image_type\":\"jdk\","
                + "\"package\":{\"link\":\"https://github.com/adoptium/pkg.zip\",\"checksum\":\"" + SHA
                + "\",\"size\":1000}}],\"timestamp\":\"2026-08-19T13:28:29Z\",\"version_data\":{}}]";
        String good = asset("21.0.12.1+1",
                "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/jdk.zip",
                "windows", "jdk", SHA);
        CatalogHttpClient client = url -> currentAsset(url, withJreOnly, withNonHttps, withBadChecksum,
                withoutVersion, good);

        List<SdkCatalogEntry> entries = new AdoptiumJavaFetcher(client, objectMapper).fetch();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getDownloadUrl()).contains("temurin21-binaries");
    }

    @Test
    void dedupesFeatureReleaseAlreadyInLastFiveLts() throws Exception {
        String info = "{\"available_lts_releases\":[8,11,17,21,25,26,27,28],"
                + "\"most_recent_feature_release\":25}";
        CatalogHttpClient client = url -> {
            if (url.equals(AdoptiumJavaFetcher.INFO_URL)) {
                return info;
            }
            return "[" + asset("21.0.12.1+1",
                    "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/jdk.zip",
                    "windows", "jdk", SHA) + "]";
        };
        // 候选 = 最后 5 个 LTS {21,25,26,27,28}（8 被淘汰） + feature 25（去重）→ 5 个 major
        List<SdkCatalogEntry> entries = new AdoptiumJavaFetcher(client, objectMapper).fetch();
        assertThat(entries).hasSize(5);
    }

    @Test
    void capsAtFiveCandidates() throws Exception {
        String info = "{\"available_lts_releases\":[8,11,17,21,25,26,27,28],"
                + "\"most_recent_feature_release\":29}";
        CatalogHttpClient client = url -> {
            if (url.equals(AdoptiumJavaFetcher.INFO_URL)) {
                return info;
            }
            return "[" + asset("21.0.12.1+1",
                    "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/jdk.zip",
                    "windows", "jdk", SHA) + "]";
        };
        // 候选 = 后 5 个 LTS {25..28} + feature 29 → 6 个 major，上限截断为 5
        List<SdkCatalogEntry> entries = new AdoptiumJavaFetcher(client, objectMapper).fetch();
        assertThat(entries).hasSize(5);
    }

    @Test
    void excludesJavaEightAndOlderCandidates() throws Exception {
        String info = "{\"available_lts_releases\":[8,11],\"most_recent_feature_release\":8}";
        CatalogHttpClient client = url -> {
            if (url.equals(AdoptiumJavaFetcher.INFO_URL)) {
                return info;
            }
            if (url.contains("feature_releases/8/ga")) {
                throw new IOException("过老 major 8 不应被探测");
            }
            return "[" + asset("11.0.30+9",
                    "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/jdk.zip",
                    "windows", "jdk", SHA) + "]";
        };

        List<SdkCatalogEntry> entries = new AdoptiumJavaFetcher(client, objectMapper).fetch();
        assertThat(entries).extracting(SdkCatalogEntry::getVersion).containsExactly("11.0.30");
    }

    @Test
    void toleratesMissingOrInvalidTimestamp() throws Exception {
        String withoutTimestamp = "{\"binaries\":[{\"os\":\"windows\",\"architecture\":\"x64\","
                + "\"image_type\":\"jdk\",\"package\":{\"link\":\"https://github.com/adoptium/pkg.zip\","
                + "\"checksum\":\"" + SHA + "\",\"size\":1000}}],"
                + "\"version_data\":{\"openjdk_version\":\"21.0.12.1+1\"}}";
        CatalogHttpClient client = url -> currentAsset(url, withoutTimestamp);
        List<SdkCatalogEntry> entries = new AdoptiumJavaFetcher(client, objectMapper).fetch();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getReleaseTime()).isNull();
    }

    /** 按 major 路由资产夹具；未知 major 返回空数组（对应官方源无 GA 资产）。 */
    private static String route(String url, Map<Integer, String> versions) throws IOException {
        if (url.equals(AdoptiumJavaFetcher.INFO_URL)) {
            return INFO_JSON;
        }
        for (Map.Entry<Integer, String> entry : versions.entrySet()) {
            if (url.contains("feature_releases/" + entry.getKey() + "/ga")) {
                return "[" + asset(entry.getValue(),
                        "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/jdk.zip",
                        "windows", "jdk", SHA) + "]";
            }
        }
        return "[]";
    }

    /** 让所有 major 请求都拿到同一份资产夹具（候选集合收敛为单一 major，聚焦资产过滤断言）。 */
    private static String currentAsset(String url, String... assets) throws IOException {
        if (url.equals(AdoptiumJavaFetcher.INFO_URL)) {
            return SINGLE_LTS_INFO_JSON;
        }
        return "[" + String.join(",", assets) + "]";
    }
}
