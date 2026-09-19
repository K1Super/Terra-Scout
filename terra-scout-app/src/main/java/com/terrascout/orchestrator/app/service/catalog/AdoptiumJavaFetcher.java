package com.terrascout.orchestrator.app.service.catalog;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.terrascout.orchestrator.core.enums.ArchEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.OsTypeEnum;

import org.springframework.stereotype.Component;

/**
 * Adoptium（Eclipse Temurin）JAVA 官方源抓取器。
 *
 * <p>数据源：
 * <ol>
 *   <li>{@code /v3/info/available_releases}：LTS 列表与最新特性版本；</li>
 *   <li>{@code /v3/assets/feature_releases/{major}/ga}（windows/x64/jdk/zip 过滤）：版本号、
 *       下载地址（package.link）、sha256（package.checksum）与发布时间（timestamp）。</li>
 * </ol>
 * 候选 major = 最后 5 个 LTS（{@link #MIN_MAJOR} 起）+ most_recent_feature_release（去重），
 * 每 major 一条，上限 5 条。Java 8 及以下过老版本（如 1.8.0_504-b01）不再收录。
 */
@Component
public class AdoptiumJavaFetcher implements LanguageCatalogFetcher {

    public static final String INFO_URL = "https://api.adoptium.net/v3/info/available_releases";

    /** 收录最低 major：低于该值的版本（Java 8 及以下）判为过老，直接淘汰。 */
    public static final int MIN_MAJOR = 11;

    static final String ASSETS_URL_FORMAT =
            "https://api.adoptium.net/v3/assets/feature_releases/%d/ga"
                    + "?architecture=x64&image_type=jdk&os=windows&vendor=eclipse";

    /** 单语言条目上限。 */
    static final int LIMIT = 5;

    /** sha256 十六进制格式（小写 64 位）。 */
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[a-f0-9]{64}$");

    private static final String LICENSE = "GPL-2.0-with-classpath-exception";
    private static final String DISTRIBUTION = "Temurin";
    private static final String VENDOR = "Eclipse Adoptium";

    private final CatalogHttpClient client;
    private final ObjectMapper objectMapper;

    public AdoptiumJavaFetcher(CatalogHttpClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<SdkCatalogEntry> fetch() throws Exception {
        JsonNode info = objectMapper.readTree(client.get(INFO_URL));
        List<Integer> ltsReleases = new ArrayList<>();
        for (JsonNode release : info.path("available_lts_releases")) {
            int major = release.asInt();
            if (major >= MIN_MAJOR) {
                ltsReleases.add(major);
            }
        }
        ltsReleases.sort(Integer::compareTo);
        Set<Integer> candidates = new LinkedHashSet<>();
        for (int i = Math.max(0, ltsReleases.size() - LIMIT); i < ltsReleases.size(); i++) {
            candidates.add(ltsReleases.get(i));
        }
        if (info.hasNonNull("most_recent_feature_release")
                && info.get("most_recent_feature_release").asInt() >= MIN_MAJOR) {
            candidates.add(info.get("most_recent_feature_release").asInt());
        }
        List<SdkCatalogEntry> result = new ArrayList<>();
        for (Integer major : candidates) {
            if (result.size() >= LIMIT) {
                break;
            }
            SdkCatalogEntry entry = fetchMajor(major, ltsReleases.contains(major));
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    /** 抓取单 major 的 GA 资产，无 windows/x64/jdk/zip 二进制的 major 返回 null。 */
    private SdkCatalogEntry fetchMajor(int major, boolean lts) throws Exception {
        JsonNode assets = objectMapper.readTree(client.get(String.format(ASSETS_URL_FORMAT, major)));
        if (!assets.isArray()) {
            return null;
        }
        for (JsonNode asset : assets) {
            SdkCatalogEntry entry = parseAsset(asset, lts);
            if (entry != null) {
                return entry;
            }
        }
        return null;
    }

    /** 从单个 release 资产解析条目；无匹配二进制或字段缺关键值时返回 null。 */
    private SdkCatalogEntry parseAsset(JsonNode asset, boolean lts) {
        for (JsonNode binary : asset.path("binaries")) {
            if (!"windows".equals(binary.path("os").asText())) {
                continue;
            }
            if (!"x64".equals(binary.path("architecture").asText())) {
                continue;
            }
            if (!"jdk".equals(binary.path("image_type").asText())) {
                continue;
            }
            JsonNode pkg = binary.path("package");
            String link = pkg.path("link").asText("");
            if (link.isEmpty() || !link.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                continue;
            }
            if (!CatalogUrlPolicy.isAllowed(link)) {
                continue;
            }
            String versionData = asset.path("version_data").path("openjdk_version").asText("");
            int plus = versionData.indexOf('+');
            String version = plus >= 0 ? versionData.substring(0, plus) : versionData;
            if (version.isEmpty()) {
                continue;
            }
            String sha256 = pkg.path("checksum").asText("").toLowerCase(Locale.ROOT);
            if (!SHA256_PATTERN.matcher(sha256).matches()) {
                continue;
            }
            SdkCatalogEntry entry = new SdkCatalogEntry();
            entry.setLanguage(LanguageEnum.JAVA);
            entry.setVersion(version);
            entry.setOs(OsTypeEnum.WINDOWS);
            entry.setArch(ArchEnum.AMD64);
            entry.setDownloadUrl(link);
            entry.setSha256(sha256);
            entry.setSizeBytes(pkg.path("size").isNumber() ? pkg.path("size").asLong() : null);
            entry.setLts(lts);
            entry.setEol(false);
            entry.setLicense(LICENSE);
            entry.setDistribution(DISTRIBUTION);
            entry.setVendor(VENDOR);
            entry.setReleaseTime(parseTimestamp(asset.path("timestamp").asText("")));
            return entry;
        }
        return null;
    }

    /** 时间戳字符串 → epoch 毫秒；缺失或非法返回 null。 */
    private static Long parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(timestamp).toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
