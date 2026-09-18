package com.terrascout.orchestrator.app.unit;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.terrascout.orchestrator.app.service.catalog.CatalogHttpClient;
import com.terrascout.orchestrator.app.service.catalog.NodeJsFetcher;
import com.terrascout.orchestrator.app.service.catalog.SdkCatalogEntry;
import com.terrascout.orchestrator.core.enums.ArchEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.OsTypeEnum;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Node.js 官方源抓取器单测（固定夹具 + 假客户端 + 固定时钟）：镜像兜底 / SHASUMS 提取与
 * 跳过 / 行级 LTS·Current 裁决（官方静态 EOL 表排除，index.json 本身不含 end 字段）/
 * 每线最多两版本策略。
 */
class NodeJsFetcherTest {

    /** 固定「今天」：18（EOL 2025-04-30）/ 20（EOL 2026-04-30）已过 EOL，22/24/26 仍受支持。 */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-18T00:00:00Z"), ZoneOffset.UTC);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 由版本号派生的 64 位小写十六进制假 sha256（仅合法性有效，非真实指纹）。 */
    private static String sha64(String seed) {
        return String.format("%064x", Math.abs(seed.hashCode()));
    }

    /** 按版本拼接 SHASUMS256.txt 内容（与官方同构：产物名保留 v 前缀）；入参为带 v 的原始版本号。 */
    private static String shasumsFor(String... versions) {
        StringBuilder builder = new StringBuilder();
        for (String version : versions) {
            builder.append(sha64(version)).append("  node-").append(version)
                    .append("-win-x64.zip\n");
        }
        return builder.toString();
    }

    private static String index(String... releases) {
        return "[" + String.join(",", releases) + "]";
    }

    @Test
    void keepsSupportedLtsAndCurrentLinesInMajorDescOrder() throws Exception {
        String index = index(
                "{\"version\":\"v25.1.0\",\"date\":\"2026-03-18\",\"lts\":false}",
                "{\"version\":\"v26.1.0\",\"date\":\"2026-09-01\",\"lts\":false}",
                "{\"version\":\"v26.0.0\",\"date\":\"2026-04-21\",\"lts\":false}",
                "{\"version\":\"v24.11.1\",\"date\":\"2026-08-12\",\"lts\":\"Krypton\"}",
                "{\"version\":\"v24.10.0\",\"date\":\"2026-07-15\",\"lts\":\"Krypton\"}",
                "{\"version\":\"v22.20.0\",\"date\":\"2025-08-26\",\"lts\":\"Jod\"}",
                "{\"version\":\"v20.19.5\",\"date\":\"2025-04-09\",\"lts\":\"Iron\"}",
                "{\"version\":\"v18.20.8\",\"date\":\"2025-03-27\",\"lts\":\"Hydrogen\"}");
        List<String> all = List.of("v26.1.0", "v26.0.0", "v24.11.1", "v24.10.0", "v22.20.0");
        CatalogHttpClient client = url -> {
            if (url.equals(NodeJsFetcher.PRIMARY_INDEX)) {
                return index;
            }
            return shasumsFor(all.toArray(new String[0]));
        };

        List<SdkCatalogEntry> entries = new NodeJsFetcher(client, objectMapper, CLOCK).fetch();

        // 受支持线倒序：26 Current（偶数且新于最高 LTS 24）→ 24 LTS → 22 LTS；
        // 20/18 已过静态表 EOL 排除、25 奇数非 LTS 排除；每线最新两个
        assertThat(entries).extracting(SdkCatalogEntry::getVersion)
                .containsExactly("26.1.0", "26.0.0", "24.11.1", "24.10.0", "22.20.0");
        SdkCatalogEntry last = entries.get(4);
        assertThat(last.isLts()).isTrue();
        assertThat(last.getLanguage()).isEqualTo(LanguageEnum.NODE);
        assertThat(last.getOs()).isEqualTo(OsTypeEnum.WINDOWS);
        assertThat(last.getArch()).isEqualTo(ArchEnum.AMD64);
        assertThat(last.getDownloadUrl()).isEqualTo(
                NodeJsFetcher.PRIMARY_BASE + "/v22.20.0/node-v22.20.0-win-x64.zip");
        assertThat(last.getSha256()).isEqualTo(sha64("v22.20.0"));
        assertThat(last.getLicense()).isEqualTo("MIT");
        assertThat(last.getDistribution()).isEqualTo("Node.js");
        assertThat(last.getVendor()).isEqualTo("OpenJS Foundation");
        assertThat(last.isEol()).isFalse();
        assertThat(last.getReleaseTime()).isEqualTo(
                LocalDate.parse("2025-08-26").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
        // Current 行条目 lts=false，日期取自 index
        assertThat(entries.get(0).isLts()).isFalse();
        assertThat(entries.get(0).getReleaseTime()).isEqualTo(
                LocalDate.parse("2026-09-01").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
    }

    @Test
    void fallsBackToMirrorWhenPrimaryUnreachable() throws Exception {
        String index = index("{\"version\":\"v22.20.0\",\"date\":\"2025-08-26\",\"lts\":\"Jod\"}");
        CatalogHttpClient client = url -> {
            if (url.equals(NodeJsFetcher.PRIMARY_INDEX)) {
                throw new IOException("primary down");
            }
            if (url.equals(NodeJsFetcher.MIRROR_INDEX)) {
                return index;
            }
            return shasumsFor("v22.20.0");
        };

        List<SdkCatalogEntry> entries = new NodeJsFetcher(client, objectMapper, CLOCK).fetch();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getDownloadUrl()).startsWith(NodeJsFetcher.MIRROR_BASE);
    }

    @Test
    void propagatesFailureWhenBothSourcesUnreachable() {
        CatalogHttpClient client = url -> {
            throw new IOException("both down");
        };
        assertThatThrownBy(() -> new NodeJsFetcher(client, objectMapper, CLOCK).fetch())
                .isInstanceOf(IOException.class);
    }

    @Test
    void skipsVersionsWithoutMatchingWinX64ShaLine() throws Exception {
        String index = index(
                "{\"version\":\"v20.19.5\",\"date\":\"2025-04-09\",\"lts\":false}",
                "{\"version\":\"v18.20.8\",\"date\":\"2025-03-27\",\"lts\":false}",
                "{\"version\":\"v22.20.0\",\"date\":\"2025-08-26\",\"lts\":\"Jod\"}");
        CatalogHttpClient client = url -> {
            if (url.equals(NodeJsFetcher.PRIMARY_INDEX)) {
                return index;
            }
            if (url.contains("v22.20.0")) {
                return shasumsFor("v22.20.0");
            }
            // v20.19.5 仅有 linux 行；v18.20.8 缺 checksum
            if (url.contains("v18.20.8")) {
                throw new IOException("sums missing");
            }
            return sha64("v20.19.5") + "  node-v20.19.5-linux-x64.tar.gz\n";
        };

        List<SdkCatalogEntry> entries = new NodeJsFetcher(client, objectMapper, CLOCK).fetch();
        // 非 LTS 的 20/18 行不满足 Current 条件（20<22 且非唯一最新偶数行）→ 不探测
        assertThat(entries).extracting(SdkCatalogEntry::getVersion).containsExactly("22.20.0");
    }

    @Test
    void handlesBooleanLtsAndMissingDate() throws Exception {
        String index = index(
                "{\"version\":\"v24.11.1\",\"date\":\"2024-11-26\",\"lts\":false}",
                "{\"version\":\"v22.20.0\",\"lts\":true}");
        CatalogHttpClient client = url -> {
            if (url.equals(NodeJsFetcher.PRIMARY_INDEX)) {
                return index;
            }
            return shasumsFor("v24.11.1", "v22.20.0");
        };

        List<SdkCatalogEntry> entries = new NodeJsFetcher(client, objectMapper, CLOCK).fetch();
        // 24：偶数且新于最高 LTS 22 → Current 纳入；22：LTS（lts=true 布尔形态）
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).isLts()).isFalse();
        assertThat(entries.get(0).getReleaseTime()).isEqualTo(
                LocalDate.parse("2024-11-26").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
        assertThat(entries.get(1).getVersion()).isEqualTo("22.20.0");
        assertThat(entries.get(1).isLts()).isTrue();
        assertThat(entries.get(1).getReleaseTime()).isNull();
    }

    @Test
    void keepsTwoPerLineAndStopsProbingAfterLimit() throws Exception {
        String index = index(
                "{\"version\":\"v24.11.1\",\"date\":\"2026-08-12\",\"lts\":\"Krypton\"}",
                "{\"version\":\"v24.10.0\",\"date\":\"2026-07-15\",\"lts\":\"Krypton\"}",
                "{\"version\":\"v24.9.0\",\"date\":\"2026-06-10\",\"lts\":\"Krypton\"}",
                "{\"version\":\"v24.8.0\",\"date\":\"2026-05-12\",\"lts\":\"Krypton\"}",
                "{\"version\":\"v24.7.0\",\"date\":\"2026-04-08\",\"lts\":\"Krypton\"}");
        AtomicInteger probes = new AtomicInteger();
        CatalogHttpClient client = url -> {
            if (url.equals(NodeJsFetcher.PRIMARY_INDEX)) {
                return index;
            }
            probes.incrementAndGet();
            return shasumsFor("v24.11.1", "v24.10.0", "v24.9.0", "v24.8.0", "v24.7.0");
        };

        List<SdkCatalogEntry> entries = new NodeJsFetcher(client, objectMapper, CLOCK).fetch();
        assertThat(entries).extracting(SdkCatalogEntry::getVersion)
                .containsExactly("24.11.1", "24.10.0");
        // 收满两条即停，更旧 patch 不再拉取 SHASUMS
        assertThat(probes.get()).isEqualTo(NodeJsFetcher.KEEP_PER_LINE);
    }

    @Test
    void excludesEolLtsLinesPerStaticTableWithoutProbing() throws Exception {
        String index = index(
                "{\"version\":\"v22.20.0\",\"date\":\"2025-08-26\",\"lts\":\"Jod\"}",
                "{\"version\":\"v20.19.5\",\"date\":\"2025-04-09\",\"lts\":\"Iron\"}",
                "{\"version\":\"v18.20.8\",\"date\":\"2025-03-27\",\"lts\":\"Hydrogen\"}");
        AtomicInteger probes = new AtomicInteger();
        CatalogHttpClient client = url -> {
            if (url.equals(NodeJsFetcher.PRIMARY_INDEX)) {
                return index;
            }
            probes.incrementAndGet();
            return shasumsFor("v22.20.0", "v20.19.5", "v18.20.8");
        };

        List<SdkCatalogEntry> entries = new NodeJsFetcher(client, objectMapper, CLOCK).fetch();
        assertThat(entries).extracting(SdkCatalogEntry::getVersion).containsExactly("22.20.0");
        // 20（EOL 2026-04-30）/ 18（EOL 2025-04-30）已过静态表 EOL → 整线排除且从未探测
        assertThat(probes.get()).isEqualTo(1);
    }

    @Test
    void includesOnlyNewestEvenCurrentLineAndExcludesOdd() throws Exception {
        String index = index(
                "{\"version\":\"v26.1.0\",\"date\":\"2026-09-01\",\"lts\":false}",
                "{\"version\":\"v25.9.0\",\"date\":\"2026-03-10\",\"lts\":false}",
                "{\"version\":\"v24.11.1\",\"date\":\"2026-08-12\",\"lts\":\"Krypton\"}");
        AtomicInteger probes = new AtomicInteger();
        CatalogHttpClient client = url -> {
            if (url.equals(NodeJsFetcher.PRIMARY_INDEX)) {
                return index;
            }
            probes.incrementAndGet();
            return shasumsFor("v26.1.0", "v25.9.0", "v24.11.1");
        };

        List<SdkCatalogEntry> entries = new NodeJsFetcher(client, objectMapper, CLOCK).fetch();
        assertThat(entries).extracting(SdkCatalogEntry::getVersion)
                .containsExactly("26.1.0", "24.11.1");
        // 25 奇数非 LTS 行排除且从未探测
        assertThat(probes.get()).isEqualTo(2);
    }

    @Test
    void includesFutureLtsLineBeyondStaticTable() throws Exception {
        String index = index(
                "{\"version\":\"v28.0.0\",\"date\":\"2028-04-01\",\"lts\":\"Zoic\"}",
                "{\"version\":\"v24.11.1\",\"date\":\"2026-08-12\",\"lts\":\"Krypton\"}");
        AtomicInteger probes = new AtomicInteger();
        CatalogHttpClient client = url -> {
            if (url.equals(NodeJsFetcher.PRIMARY_INDEX)) {
                return index;
            }
            probes.incrementAndGet();
            return shasumsFor("v28.0.0", "v24.11.1");
        };

        List<SdkCatalogEntry> entries = new NodeJsFetcher(client, objectMapper, CLOCK).fetch();
        // 28 在 EOL 表上界（26）之外且有 LTS 条目 → 未来受支持线 → 纳入
        assertThat(entries).extracting(SdkCatalogEntry::getVersion)
                .containsExactly("28.0.0", "24.11.1");
        assertThat(probes.get()).isEqualTo(2);
    }

    @Test
    void excludesAncientLtsMajorBelowStaticTable() throws Exception {
        String index = index(
                "{\"version\":\"v22.20.0\",\"date\":\"2025-08-26\",\"lts\":\"Jod\"}",
                "{\"version\":\"v10.24.1\",\"date\":\"2021-04-06\",\"lts\":\"Dubnium\"}");
        AtomicInteger probes = new AtomicInteger();
        CatalogHttpClient client = url -> {
            if (url.equals(NodeJsFetcher.PRIMARY_INDEX)) {
                return index;
            }
            probes.incrementAndGet();
            return shasumsFor("v22.20.0", "v10.24.1");
        };

        List<SdkCatalogEntry> entries = new NodeJsFetcher(client, objectMapper, CLOCK).fetch();
        // 10 有 LTS 代号但位于表上界之下且不在静态表内 → 远古行排除且从未探测
        assertThat(entries).extracting(SdkCatalogEntry::getVersion).containsExactly("22.20.0");
        assertThat(probes.get()).isEqualTo(1);
    }

    @Test
    void ltsLineSupportedOnEolDayAndExcludedAfter() throws Exception {
        String index = index("{\"version\":\"v22.20.0\",\"date\":\"2025-08-26\",\"lts\":\"Jod\"}");
        CatalogHttpClient client = url -> {
            if (url.equals(NodeJsFetcher.PRIMARY_INDEX)) {
                return index;
            }
            return shasumsFor("v22.20.0");
        };
        // 22 的 EOL 日为 2027-04-30：当日仍受支持
        Clock eolDay = Clock.fixed(Instant.parse("2027-04-30T00:00:00Z"), ZoneOffset.UTC);
        assertThat(new NodeJsFetcher(client, objectMapper, eolDay).fetch())
                .extracting(SdkCatalogEntry::getVersion).containsExactly("22.20.0");
        // EOL 次日整线排除
        Clock dayAfter = Clock.fixed(Instant.parse("2027-05-01T00:00:00Z"), ZoneOffset.UTC);
        assertThat(new NodeJsFetcher(client, objectMapper, dayAfter).fetch()).isEmpty();
    }

    @Test
    void ignoresNonArrayResponse() throws Exception {
        CatalogHttpClient client = url -> "{\"error\":true}";
        assertThat(new NodeJsFetcher(client, objectMapper, CLOCK).fetch()).isEmpty();
    }
}
