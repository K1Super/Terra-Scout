package com.terrascout.orchestrator.app.service.catalog;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.terrascout.orchestrator.core.enums.ArchEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.OsTypeEnum;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Go 官方源抓取器。
 *
 * <p>数据源：{@code https://go.dev/dl/?mode=json&include=all}（官方源不可达时回退
 * 境内同构镜像 {@code https://golang.google.cn/dl/?mode=json&include=all}）。仅收录
 * stable 版本中 os=windows / arch=amd64 / kind=archive 且文件名以
 * {@code .windows-amd64.zip} 结尾的归档，sha256 / size 直接取自官方响应；
 * 下载地址改写为 {@code https://dl.google.com/go/}（境内 CDN 可达，白名单已收录）。
 *
 * <p>版本线策略：stable 版本按 minor 归组为「版本线」，仅收录最新的
 * {@value #SUPPORTED_LINES} 个 minor 线（Go 官方仅维护最近两大版本）；每条线按版本
 * 倒序至多探测 {@value #PROBE_PER_LINE} 个、最多保留 {@value #KEEP_PER_LINE} 个成功
 * 条目——覆盖完整受支持版本线，而非仅最新窗口的 5 条。
 *
 * <p>go.dev 响应不含发布日期：经 GitHub 官方仓库 tags → commit 二级查询补全
 * （tag {@code go{v}} 的 commit.committer.date 即发布时刻）；GitHub API 不可达时
 * 优雅降级为无日期，不阻断主流程。
 */
@Component
public class GoFetcher implements LanguageCatalogFetcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoFetcher.class);

    static final String INDEX_URL = "https://go.dev/dl/?mode=json&include=all";

    /** 境内同构镜像索引（go.dev 不可达时兜底）。 */
    static final String INDEX_URL_FALLBACK = "https://golang.google.cn/dl/?mode=json&include=all";

    /** GitHub 官方仓库 tags 分页接口（tag name → commit sha）。 */
    static final String TAGS_URL_FORMAT = "https://api.github.com/repos/golang/go/tags?per_page=100&page=%d";

    /** GitHub 单 commit 查询（取得 committer.date）。 */
    static final String COMMIT_URL_FORMAT = "https://api.github.com/repos/golang/go/commits/%s";

    /** tags 最大翻页数（候选 ≤4 个，全部位于首页；上限防御异常数据）。 */
    private static final int MAX_TAG_PAGES = 3;

    /** 受支持版本线数量（Go 官方仅维护最近两个 minor 线）。 */
    public static final int SUPPORTED_LINES = 2;

    /** 每条版本线最多探测的版本数（windows 归档缺失时顺延）。 */
    public static final int PROBE_PER_LINE = 4;

    /** 每条版本线最多保留的成功条目数（取最新稳定 patch）。 */
    public static final int KEEP_PER_LINE = 2;

    /** minor 版本线提取（如 "1.22.4" → "1.22"）。 */
    private static final Pattern LINE_PATTERN = Pattern.compile("^(\\d+\\.\\d+)");

    /** sha256 十六进制格式（小写 64 位）。 */
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[a-f0-9]{64}$");

    private static final String DOWNLOAD_PREFIX = "https://dl.google.com/go/";
    private static final String LICENSE = "BSD-3-Clause";
    private static final String DISTRIBUTION = "Official";
    private static final String VENDOR = "Google";

    private final CatalogHttpClient client;
    private final ObjectMapper objectMapper;

    public GoFetcher(CatalogHttpClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<SdkCatalogEntry> fetch() throws Exception {
        JsonNode releases = fetchIndex();
        if (!releases.isArray()) {
            return List.of();
        }
        Map<String, List<JsonNode>> lines = new TreeMap<>(
                (left, right) -> VersionComparator.compare(right, left));
        for (JsonNode release : releases) {
            if (!release.path("stable").asBoolean(false)) {
                continue;
            }
            String rawVersion = release.path("version").asText("");
            if (rawVersion.isEmpty() || !rawVersion.startsWith("go")) {
                continue;
            }
            String line = lineOf(rawVersion.substring(2));
            if (line == null) {
                continue;
            }
            lines.computeIfAbsent(line, key -> new ArrayList<>()).add(release);
        }
        List<SdkCatalogEntry> result = new ArrayList<>();
        int lineCount = 0;
        for (List<JsonNode> lineReleases : lines.values()) {
            if (lineCount >= SUPPORTED_LINES) {
                break;
            }
            lineCount++;
            lineReleases.sort((left, right) -> VersionComparator.compare(versionOf(right), versionOf(left)));
            int kept = 0;
            int probed = 0;
            for (JsonNode release : lineReleases) {
                if (kept >= KEEP_PER_LINE || probed >= PROBE_PER_LINE) {
                    break;
                }
                probed++;
                SdkCatalogEntry entry = parseFiles(release, versionOf(release));
                if (entry != null) {
                    result.add(entry);
                    kept++;
                }
            }
        }
        Set<String> wanted = new HashSet<>();
        for (SdkCatalogEntry entry : result) {
            wanted.add(entry.getVersion());
        }
        Map<String, Long> releaseDates = fetchReleaseDates(wanted);
        for (SdkCatalogEntry entry : result) {
            entry.setReleaseTime(releaseDates.get(entry.getVersion()));
        }
        return result;
    }

    /** "go" 前缀剥离后的版本号（仅对已分组条目调用，前缀已校验）。 */
    private static String versionOf(JsonNode release) {
        return release.path("version").asText("").substring(2);
    }

    /** "1.22.4" → "1.22"；无法提取返回 null。 */
    private static String lineOf(String version) {
        Matcher matcher = LINE_PATTERN.matcher(version);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 拉取索引 JSON：go.dev 不可达时回退境内同构镜像；两者皆失败抛首异常。 */
    private JsonNode fetchIndex() throws Exception {
        try {
            return objectMapper.readTree(client.get(INDEX_URL));
        } catch (IOException | RuntimeException first) {
            LOGGER.warn("Go 官方索引不可达，尝试镜像 {}: {}", INDEX_URL_FALLBACK, first.getMessage());
            try {
                return objectMapper.readTree(client.get(INDEX_URL_FALLBACK));
            } catch (IOException | RuntimeException second) {
                LOGGER.warn("Go 镜像索引亦不可达: {}", second.getMessage());
                throw first;
            }
        }
    }

    /** 从 release.files 中找 windows/amd64/archive 的 zip 文件并构造条目；无则返回 null。 */
    private SdkCatalogEntry parseFiles(JsonNode release, String version) {
        for (JsonNode file : release.path("files")) {
            if (!"windows".equals(file.path("os").asText())) {
                continue;
            }
            if (!"amd64".equals(file.path("arch").asText())) {
                continue;
            }
            if (!"archive".equals(file.path("kind").asText())) {
                continue;
            }
            String filename = file.path("filename").asText("");
            if (!filename.toLowerCase(Locale.ROOT).endsWith(".windows-amd64.zip")) {
                continue;
            }
            String url = DOWNLOAD_PREFIX + filename;
            if (!CatalogUrlPolicy.isAllowed(url)) {
                continue;
            }
            String sha256 = file.path("sha256").asText("").toLowerCase(Locale.ROOT);
            if (!SHA256_PATTERN.matcher(sha256).matches()) {
                continue;
            }
            SdkCatalogEntry entry = new SdkCatalogEntry();
            entry.setLanguage(LanguageEnum.GO);
            entry.setVersion(version);
            entry.setOs(OsTypeEnum.WINDOWS);
            entry.setArch(ArchEnum.AMD64);
            entry.setDownloadUrl(url);
            entry.setSha256(sha256);
            entry.setSizeBytes(file.path("size").isNumber() ? file.path("size").asLong() : null);
            entry.setLts(false);
            entry.setEol(false);
            entry.setLicense(LICENSE);
            entry.setDistribution(DISTRIBUTION);
            entry.setVendor(VENDOR);
            return entry;
        }
        return null;
    }

    /** 经 GitHub tags → commit 补全发布日期（version → epoch 毫秒）；不可达时优雅降级。 */
    private Map<String, Long> fetchReleaseDates(Set<String> wanted) {
        Map<String, Long> dates = new HashMap<>();
        if (wanted.isEmpty()) {
            return dates;
        }
        Set<String> missing = new HashSet<>(wanted);
        Map<String, String> shas = new HashMap<>();
        int page = 1;
        while (!missing.isEmpty() && page <= MAX_TAG_PAGES) {
            try {
                JsonNode tags = objectMapper.readTree(
                        client.get(String.format(TAGS_URL_FORMAT, page)));
                if (!tags.isArray()) {
                    return dates;
                }
                for (JsonNode tag : tags) {
                    String name = tag.path("name").asText("");
                    if (!name.startsWith("go")) {
                        continue;
                    }
                    String version = name.substring(2);
                    if (!missing.contains(version)) {
                        continue;
                    }
                    String sha = tag.path("commit").path("sha").asText("");
                    if (!sha.isEmpty()) {
                        shas.put(version, sha);
                        missing.remove(version);
                    }
                }
                if (missing.isEmpty() || page >= MAX_TAG_PAGES) {
                    break;
                }
                page++;
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Go tags API 拉取失败（降级为无日期）: {}", e.getMessage());
                return dates;
            }
        }
        for (Map.Entry<String, String> tagEntry : shas.entrySet()) {
            try {
                JsonNode commit = objectMapper.readTree(
                        client.get(String.format(COMMIT_URL_FORMAT, tagEntry.getValue())));
                Long releaseTime = parseCommitDate(
                        commit.path("commit").path("committer").path("date").asText(""));
                if (releaseTime != null) {
                    dates.put(tagEntry.getKey(), releaseTime);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Go commit API 拉取失败（降级为无日期）: {}", e.getMessage());
                break;
            }
        }
        return dates;
    }

    /** GitHub committer.date（ISO-8601）→ epoch 毫秒；缺失或非法返回 null。 */
    private static Long parseCommitDate(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
