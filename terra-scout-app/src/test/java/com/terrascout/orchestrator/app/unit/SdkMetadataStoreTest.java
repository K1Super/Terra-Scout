package com.terrascout.orchestrator.app.unit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.terrascout.orchestrator.app.repository.SdkVersionRepository;
import com.terrascout.orchestrator.app.service.SdkMetadataStore;
import com.terrascout.orchestrator.app.service.catalog.SdkCatalogEntry;
import com.terrascout.orchestrator.core.constant.PathConstants;
import com.terrascout.orchestrator.core.domain.SdkVersion;
import com.terrascout.orchestrator.core.enums.CveSeverityEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.OsTypeEnum;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SDK 元数据存储单测：seed 落盘幂等 / schema 解析强校验（非法条目跳过）/ upsert 插入与更新分支 /
 * 外部文件写回与回读换算。
 */
class SdkMetadataStoreTest {

    private static final String SHA_UPPER = "E53A79C3C3D86865BD7E787903884331068E71321714FFD44F145785AFFC7CB0";

    private static final String SHA_LOWER = SHA_UPPER.toLowerCase(Locale.ROOT);

    private static final String GOOD_URL =
            "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/jdk.zip";

    private final SdkVersionRepository repository = mock(SdkVersionRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SdkMetadataStore store = new SdkMetadataStore(repository, objectMapper);

    @TempDir
    Path dataRoot;

    private static SdkCatalogEntry entry(LanguageEnum language, String version) {
        SdkCatalogEntry entry = new SdkCatalogEntry();
        entry.setLanguage(language);
        entry.setVersion(version);
        entry.setOs(OsTypeEnum.WINDOWS);
        entry.setArch(com.terrascout.orchestrator.core.enums.ArchEnum.AMD64);
        entry.setDownloadUrl(GOOD_URL);
        entry.setSha256(SHA_LOWER);
        entry.setLts(true);
        return entry;
    }

    private static long epochOf(String date) {
        return LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    @Test
    void extractSeedIfAbsentIsIdempotent() throws Exception {
        Path metadata = PathConstants.sdkMetadata(dataRoot);
        store.extractSeedIfAbsent(dataRoot);
        assertThat(metadata).isRegularFile();
        assertThat(Files.readString(metadata)).contains("\"sdks\"");

        // 已存在时不覆盖：用户后续刷新结果保持
        Files.writeString(metadata, "{\"custom\":true}");
        store.extractSeedIfAbsent(dataRoot);
        assertThat(Files.readString(metadata)).isEqualTo("{\"custom\":true}");
    }

    @Test
    void loadFromFileParsesValidEntriesAndSkipsInvalid() throws Exception {
        String json = "{"
                + "\"schemaVersion\":\"1.0\","
                + "\"source\":\"manual\","
                + "\"sdks\":["
                + "{\"language\":\"JAVA\",\"version\":\"17.0.20.1\",\"os\":\"WINDOWS\",\"arch\":\"AMD64\","
                + "\"url\":\"" + GOOD_URL + "\",\"sha256\":\"" + SHA_UPPER + "\",\"sizeBytes\":190817615,"
                + "\"lts\":true,\"eol\":false,\"eolDate\":\"2029-10-31\",\"cveCount\":2,"
                + "\"highestCveSeverity\":\"HIGH\",\"license\":\"GPL-2.0-with-classpath-exception\","
                + "\"distribution\":\"Temurin\",\"vendor\":\"Eclipse Adoptium\",\"releaseDate\":\"2026-08-19\"},"
                + "{\"language\":\"NODE\",\"version\":\"20.19.5\",\"os\":\"WINDOWS\",\"arch\":\"AMD64\","
                + "\"url\":\"https://nodejs.org/dist/v20.19.5/node.zip\",\"sha256\":\"" + SHA_LOWER + "\","
                + "\"lts\":true,\"eol\":false,\"highestCveSeverity\":\"WHATEVER\","
                + "\"releaseDate\":\"not-a-date\"},"
                + "{\"language\":\"CPP\",\"version\":\"1.0\",\"os\":\"WINDOWS\",\"arch\":\"AMD64\","
                + "\"url\":\"https://nodejs.org/x.zip\",\"sha256\":\"" + SHA_LOWER + "\","
                + "\"lts\":true,\"eol\":false},"
                + "{\"language\":\"GO\",\"version\":\"\",\"os\":\"WINDOWS\",\"arch\":\"AMD64\","
                + "\"url\":\"https://nodejs.org/x.zip\",\"sha256\":\"" + SHA_LOWER + "\","
                + "\"lts\":true,\"eol\":false},"
                + "{\"language\":\"JAVA\",\"version\":\"1.0\",\"os\":\"WINDOWS\",\"arch\":\"AMD64\","
                + "\"url\":\"http://evil.example.com/x.zip\",\"sha256\":\"" + SHA_LOWER + "\","
                + "\"lts\":true,\"eol\":false},"
                + "{\"language\":\"JAVA\",\"version\":\"1.0\",\"os\":\"WINDOWS\",\"arch\":\"AMD64\","
                + "\"url\":\"https://nodejs.org/x.zip\",\"sha256\":\"bad\",\"lts\":true,\"eol\":false},"
                + "{\"language\":\"JAVA\",\"version\":\"1.0\",\"os\":\"WINDOWS\",\"arch\":\"AMD64\","
                + "\"url\":\"https://nodejs.org/x.zip\",\"sha256\":\"" + SHA_LOWER + "\",\"eol\":false},"
                + "{\"language\":\"JAVA\",\"version\":\"1.0\",\"os\":\"WINDOWS\",\"arch\":\"AMD64\","
                + "\"lts\":true,\"eol\":false}"
                + "]}";
        Path file = dataRoot.resolve("input.json");
        Files.writeString(file, json);

        List<SdkCatalogEntry> entries = store.loadFromFile(file);

        assertThat(entries).hasSize(2);
        SdkCatalogEntry full = entries.get(0);
        assertThat(full.getLanguage()).isEqualTo(LanguageEnum.JAVA);
        assertThat(full.getVersion()).isEqualTo("17.0.20.1");
        assertThat(full.getDownloadUrl()).isEqualTo(GOOD_URL);
        assertThat(full.getSha256()).isEqualTo(SHA_LOWER);
        assertThat(full.getSizeBytes()).isEqualTo(190817615L);
        assertThat(full.isLts()).isTrue();
        assertThat(full.isEol()).isFalse();
        assertThat(full.getEolDate()).isEqualTo(LocalDate.parse("2029-10-31"));
        assertThat(full.getCveCount()).isEqualTo(2);
        assertThat(full.getHighestCveSeverity()).isEqualTo(CveSeverityEnum.HIGH);
        assertThat(full.getLicense()).isEqualTo("GPL-2.0-with-classpath-exception");
        assertThat(full.getDistribution()).isEqualTo("Temurin");
        assertThat(full.getVendor()).isEqualTo("Eclipse Adoptium");
        assertThat(full.getReleaseTime()).isEqualTo(epochOf("2026-08-19"));

        // 缺省字段回退：非法 severity → NONE、缺 cveCount → 0、非法/缺失 releaseDate → null
        SdkCatalogEntry defaults = entries.get(1);
        assertThat(defaults.getCveCount()).isZero();
        assertThat(defaults.getHighestCveSeverity()).isEqualTo(CveSeverityEnum.NONE);
        assertThat(defaults.getReleaseTime()).isNull();
        assertThat(defaults.getEolDate()).isNull();
    }

    @Test
    void loadFromFileMissingSdksArrayThrows() throws Exception {
        Path file = dataRoot.resolve("bad.json");
        Files.writeString(file, "{\"schemaVersion\":\"1.0\",\"entries\":[{}]}");
        assertThatThrownBy(() -> store.loadFromFile(file)).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void loadFromFileAbsentReturnsEmpty() throws Exception {
        assertThat(store.loadFromFile(dataRoot.resolve("missing.json"))).isEmpty();
    }

    @Test
    void upsertAllInsertsNewEntity() {
        when(repository.findByLanguageAndVersionAndOsAndArch(any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        int count = store.upsertAll(List.of(entry(LanguageEnum.JAVA, "17.0.20.1")));

        assertThat(count).isEqualTo(1);
        ArgumentCaptor<SdkVersion> captor = ArgumentCaptor.forClass(SdkVersion.class);
        verify(repository).save(captor.capture());
        SdkVersion saved = captor.getValue();
        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getCreatedAt()).isGreaterThan(0);
        assertThat(saved.getLanguage()).isEqualTo(LanguageEnum.JAVA);
        assertThat(saved.getVersion()).isEqualTo("17.0.20.1");
        assertThat(saved.getSha256()).isEqualTo(SHA_LOWER);
        assertThat(saved.getHighestCveSeverity()).isEqualTo(CveSeverityEnum.NONE);
    }

    @Test
    void upsertAllUpdatesExistingEntityPreservingIdAndCreatedAt() {
        SdkVersion existing = new SdkVersion();
        existing.setId("keep-me");
        existing.setCreatedAt(123L);
        existing.setLanguage(LanguageEnum.JAVA);
        existing.setVersion("old");
        existing.setOs(OsTypeEnum.WINDOWS);
        existing.setArch(com.terrascout.orchestrator.core.enums.ArchEnum.AMD64);
        existing.setDownloadUrl(GOOD_URL);
        existing.setSha256(SHA_LOWER);
        existing.setHighestCveSeverity(CveSeverityEnum.NONE);
        when(repository.findByLanguageAndVersionAndOsAndArch(any(), any(), any(), any()))
                .thenReturn(Optional.of(existing));

        int count = store.upsertAll(List.of(entry(LanguageEnum.JAVA, "21.0.12.1")));

        assertThat(count).isEqualTo(1);
        verify(repository).save(existing);
        assertThat(existing.getVersion()).isEqualTo("21.0.12.1");
        assertThat(existing.getId()).isEqualTo("keep-me");
        assertThat(existing.getCreatedAt()).isEqualTo(123L);
    }

    @Test
    void upsertAllDoesNotClobberExistingReleaseTimeWithNull() {
        SdkVersion existing = new SdkVersion();
        existing.setId("keep-me");
        existing.setCreatedAt(123L);
        existing.setLanguage(LanguageEnum.JAVA);
        existing.setVersion("17.0.20.1");
        existing.setOs(OsTypeEnum.WINDOWS);
        existing.setArch(com.terrascout.orchestrator.core.enums.ArchEnum.AMD64);
        existing.setDownloadUrl(GOOD_URL);
        existing.setSha256(SHA_LOWER);
        existing.setHighestCveSeverity(CveSeverityEnum.NONE);
        existing.setReleaseTime(epochOf("2026-08-19"));
        when(repository.findByLanguageAndVersionAndOsAndArch(any(), any(), any(), any()))
                .thenReturn(Optional.of(existing));

        // entry() 不携带 releaseTime：模拟官方日期源故障降级为无日期的刷新结果
        store.upsertAll(List.of(entry(LanguageEnum.JAVA, "17.0.20.1")));

        verify(repository).save(existing);
        assertThat(existing.getReleaseTime()).isEqualTo(epochOf("2026-08-19"));
    }

    @Test
    void upsertAllUpdatesReleaseTimeWhenIncomingEntryHasDate() {
        SdkVersion existing = new SdkVersion();
        existing.setId("keep-me");
        existing.setCreatedAt(123L);
        existing.setLanguage(LanguageEnum.JAVA);
        existing.setVersion("17.0.20.1");
        existing.setOs(OsTypeEnum.WINDOWS);
        existing.setArch(com.terrascout.orchestrator.core.enums.ArchEnum.AMD64);
        existing.setDownloadUrl(GOOD_URL);
        existing.setSha256(SHA_LOWER);
        existing.setHighestCveSeverity(CveSeverityEnum.NONE);
        existing.setReleaseTime(epochOf("2024-01-01"));
        when(repository.findByLanguageAndVersionAndOsAndArch(any(), any(), any(), any()))
                .thenReturn(Optional.of(existing));

        SdkCatalogEntry incoming = entry(LanguageEnum.JAVA, "17.0.20.1");
        incoming.setReleaseTime(epochOf("2026-08-19"));
        store.upsertAll(List.of(incoming));

        assertThat(existing.getReleaseTime()).isEqualTo(epochOf("2026-08-19"));
    }

    @Test
    void writeExternalFileRoundTripsSchema() throws Exception {
        SdkVersion entity = new SdkVersion();
        entity.setId("e1");
        entity.setCreatedAt(1L);
        entity.setLanguage(LanguageEnum.PYTHON);
        entity.setVersion("3.12.10");
        entity.setOs(OsTypeEnum.WINDOWS);
        entity.setArch(com.terrascout.orchestrator.core.enums.ArchEnum.AMD64);
        entity.setDownloadUrl("https://www.python.org/ftp/python/3.12.10/python-3.12.10-amd64.zip");
        entity.setSha256(SHA_LOWER);
        entity.setSizeBytes(100L);
        entity.setLts(false);
        entity.setEol(false);
        entity.setEolDate(LocalDate.parse("2029-10-31"));
        entity.setCveCount(2);
        entity.setHighestCveSeverity(CveSeverityEnum.HIGH);
        entity.setLicense("PSF-2.0");
        entity.setDistribution("CPython");
        entity.setVendor("Python Software Foundation");
        entity.setReleaseTime(epochOf("2026-08-19"));
        when(repository.findAll()).thenReturn(List.of(entity));

        Path file = dataRoot.resolve("config/sdk-metadata.json");
        store.writeExternalFile(file);

        JsonNode root = objectMapper.readTree(file.toFile());
        assertThat(root.path("schemaVersion").asText()).isEqualTo("1.0");
        assertThat(root.path("source").asText()).isEqualTo("official-refresh");
        assertThat(root.path("updatedAt").asText()).isNotBlank();
        JsonNode sdk = root.path("sdks").get(0);
        assertThat(sdk.path("url").asText()).isEqualTo(entity.getDownloadUrl());
        assertThat(sdk.path("releaseDate").asText()).isEqualTo("2026-08-19");
        assertThat(sdk.path("sha256").asText()).isEqualTo(SHA_LOWER);
        assertThat(sdk.path("sizeBytes").asLong()).isEqualTo(100L);
        assertThat(sdk.path("lts").asBoolean()).isFalse();
        assertThat(sdk.path("highestCveSeverity").asText()).isEqualTo("HIGH");
        assertThat(sdk.path("dependencyCount").isMissingNode()).isTrue();

        // 回读换算：releaseDate → UTC 零点 epoch 毫秒
        List<SdkCatalogEntry> entries = store.loadFromFile(file);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getVersion()).isEqualTo("3.12.10");
        assertThat(entries.get(0).getReleaseTime()).isEqualTo(epochOf("2026-08-19"));
    }
}
