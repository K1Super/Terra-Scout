package com.terrascout.orchestrator.app.service.catalog;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.terrascout.orchestrator.core.enums.ArchEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.OsTypeEnum;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Python（CPython）官方源抓取器。
 *
 * <p>数据源：
 * <ol>
 *   <li>{@code https://www.python.org/ftp/python/} 目录页 HTML，正则提取
 *       {@code (\d+\.\d+\.\d+)/} 稳定版目录（天然排除 a/b/rc 预发布）；</li>
 *   <li>逐候选版本请求 {@code /ftp/python/{v}/windows-{v}.json}：取首个 url 含
 *       {@code -amd64.zip} 且不含 embed 的最小发行存档及其 hash.sha256；</li>
 *   <li>{@code https://www.python.org/api/v2/downloads/release/} 官方 API 补全各版本
 *       release_date（目录页与 windows 元数据均无日期字段；不可达时优雅降级为无日期）。
 *   该 API 现行返回全量数组（忽略分页参数），alpha/beta/rc 等 {@code pre_release}
 *       条目先行跳过，避免抢占正式版日期；如将来改回 DRF 分页形态则逐页兜底。</li>
 * </ol>
 *
 * <p>版本线策略：目录版本按 major.minor 归组为「版本线」；行级 EOL
 * 静态表（官方 PEP 发布计划）排除已停维的行（表外行：新于表内最高行视为未来受支持行，
 * 旧于表内最低行视为已 EOL 排除）；每条受支持线按版本倒序至多探测
 * {@value #PROBE_PER_LINE} 个、最多保留 {@value #KEEP_PER_LINE} 个成功条目——
 * 覆盖完整受支持旧版本线（3.10/3.11/3.12/3.13/3.14…最新稳定 patch），而非仅最新窗口。
 * 安全维护期发布为「仅源码」（无 windows 元数据）时顺延深挖，直至取到该线最近
 * 可安装的最新 patch（探测深度按年累积源码发布计入）。
 */
@Component
public class PythonFetcher implements LanguageCatalogFetcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(PythonFetcher.class);

    public static final String INDEX_URL = "https://www.python.org/ftp/python/";

    /** windows 元数据地址模板。 */
    static final String WINDOWS_JSON_FORMAT = "https://www.python.org/ftp/python/%s/windows-%s.json";

    /** 版本发布日期 API（官方 downloads release 全量数组，一次请求覆盖全部候选）。 */
    static final String RELEASE_DATES_API = "https://www.python.org/api/v2/downloads/release/";

    /** 日期 API 最大翻页数（仅 DRF 分页兜底路径使用，防御异常数据导致无限翻页）。 */
    private static final int MAX_DATE_PAGES = 3;

    /**
     * 每条版本线最多探测的版本数（windows 元数据缺失时顺延）。安全维护期发布为
     * 「仅源码」（无 Windows 产物），行首可能堆叠多年源码 patch——如 3.11 线的
     * 3.11.10~3.11.16 均为源码发布，直到 3.11.9 才有 windows 元数据；16 次足以
     * 覆盖当前维护期各线的源码发布累积（收满两条即停，实际探测远小于上限）。
     */
    public static final int PROBE_PER_LINE = 16;

    /** 每条版本线最多保留的成功条目数（取最新稳定 patch）。 */
    public static final int KEEP_PER_LINE = 2;

    /** 行级 EOL 静态表（官方 PEP 发布计划：major.minor → EOL 日期，到该日期当日仍受支持）。 */
    private static final Map<String, LocalDate> LINE_EOL = Map.of(
            "3.9", LocalDate.of(2025, 10, 31),
            "3.10", LocalDate.of(2026, 10, 31),
            "3.11", LocalDate.of(2027, 10, 31),
            "3.12", LocalDate.of(2028, 10, 31),
            "3.13", LocalDate.of(2029, 10, 31),
            "3.14", LocalDate.of(2030, 10, 31));

    /** 表内最高版本线：表外新行（如 3.15）视为未来受支持行。 */
    private static final String MAX_TABLE_LINE = "3.14";

    /** major.minor 版本线提取（如 "3.14.0" → "3.14"）。 */
    private static final Pattern LINE_PATTERN = Pattern.compile("^(\\d+\\.\\d+)");

    /** 目录页稳定版目录名。 */
    private static final Pattern STABLE_DIR_PATTERN = Pattern.compile("href=\"(\\d+\\.\\d+\\.\\d+)/\"");

    /** API 结果 name 字段中的版本号（兼容 "3.13.1" 与 "Python 3.13.1" 两种形态）。 */
    private static final Pattern API_VERSION_PATTERN = Pattern.compile("(\\d+\\.\\d+\\.\\d+)");

    /** sha256 十六进制格式（小写 64 位）。 */
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[a-f0-9]{64}$");

    private static final String LICENSE = "PSF-2.0";
    private static final String DISTRIBUTION = "CPython";
    private static final String VENDOR = "Python Software Foundation";

    private final CatalogHttpClient client;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public PythonFetcher(CatalogHttpClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, Clock.systemUTC());
    }

    /** 测试注入固定时钟，以确定性裁决行级 EOL（生产走系统 UTC 时钟）。 */
    public PythonFetcher(CatalogHttpClient client, ObjectMapper objectMapper, Clock clock) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public List<SdkCatalogEntry> fetch() throws Exception {
        String html = client.get(INDEX_URL);
        Set<String> versions = new LinkedHashSet<>();
        Matcher matcher = STABLE_DIR_PATTERN.matcher(html);
        while (matcher.find()) {
            versions.add(matcher.group(1));
        }
        List<String> all = new ArrayList<>(versions);
        all.sort((left, right) -> VersionComparator.compare(right, left));
        LocalDate today = LocalDate.now(clock);
        Map<String, List<String>> lines = new LinkedHashMap<>();
        for (String version : all) {
            String line = lineOf(version);
            if (line == null || !isSupportedLine(line, today)) {
                continue;
            }
            lines.computeIfAbsent(line, key -> new ArrayList<>()).add(version);
        }
        List<String> wanted = new ArrayList<>();
        for (List<String> lineVersions : lines.values()) {
            for (int i = 0; i < lineVersions.size() && i < PROBE_PER_LINE; i++) {
                wanted.add(lineVersions.get(i));
            }
        }
        Map<String, Long> releaseDates = fetchReleaseDates(wanted);
        List<SdkCatalogEntry> result = new ArrayList<>();
        for (List<String> lineVersions : lines.values()) {
            int kept = 0;
            int probed = 0;
            for (String version : lineVersions) {
                if (kept >= KEEP_PER_LINE || probed >= PROBE_PER_LINE) {
                    break;
                }
                probed++;
                SdkCatalogEntry entry = fetchWindows(version, releaseDates);
                if (entry != null) {
                    result.add(entry);
                    kept++;
                }
            }
        }
        return result;
    }

    /** "3.14.0" → "3.14"；无法提取返回 null。 */
    private static String lineOf(String version) {
        Matcher matcher = LINE_PATTERN.matcher(version);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 行级受支持裁决：表内行在 EOL 日期（含当日）内视为受支持；
     * 表外行新于表内最高行视为未来受支持行，旧行（如 2.7/3.8）视为已 EOL 排除。
     */
    private static boolean isSupportedLine(String line, LocalDate today) {
        LocalDate eol = LINE_EOL.get(line);
        if (eol != null) {
            return !today.isAfter(eol);
        }
        return VersionComparator.compare(line, MAX_TABLE_LINE) > 0;
    }

    /**
     * 从官方 downloads API 补全候选版本发布日期（version → epoch 毫秒）。
     * 现行 API 返回全量数组（一次覆盖所有候选）；若响应退化为 DRF
     * {@code {results, next}} 分页形态则逐页兜底。API 不可达 / 结构异常
     * 一律优雅降级为空，不阻断主流程。
     */
    private Map<String, Long> fetchReleaseDates(List<String> candidates) {
        Map<String, Long> dates = new HashMap<>();
        try {
            JsonNode root = objectMapper.readTree(client.get(RELEASE_DATES_API));
            if (root.isArray()) {
                collectReleaseDates(root, candidates, dates);
            } else {
                fetchReleaseDatesPaged(root, candidates, dates);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Python 发布日期 API 拉取失败（降级为无日期）: {}", e.getMessage());
        }
        return dates;
    }

    /**
     * 遍历 releases 数组收集候选版本日期；pre_release（alpha/beta/rc）先行跳过，
     * 避免如「Python 3.14.0a1」先于正式版出现时抢占 3.14.0 的日期。
     */
    private static void collectReleaseDates(JsonNode releases, List<String> candidates,
                                            Map<String, Long> dates) {
        Set<String> missing = new HashSet<>(candidates);
        for (JsonNode item : releases) {
            if (item.path("pre_release").asBoolean(false)) {
                continue;
            }
            Matcher nameMatcher = API_VERSION_PATTERN.matcher(item.path("name").asText(""));
            String version = nameMatcher.find() ? nameMatcher.group() : null;
            if (version == null || !missing.contains(version)) {
                continue;
            }
            Long releaseTime = parseReleaseDate(item.path("release_date").asText(""));
            if (releaseTime != null) {
                dates.put(version, releaseTime);
                missing.remove(version);
            }
        }
    }

    /** DRF 分页形态兜底：沿 {@code next} 逐页收集，日期集齐或翻页封顶即止。 */
    private void fetchReleaseDatesPaged(JsonNode pageRoot, List<String> candidates,
                                        Map<String, Long> dates) throws IOException {
        JsonNode root = pageRoot;
        for (int page = 1; page <= MAX_DATE_PAGES; page++) {
            JsonNode results = root.path("results");
            if (!results.isArray()) {
                return;
            }
            collectReleaseDates(results, candidates, dates);
            if (dates.size() >= candidates.size()) {
                return;
            }
            JsonNode next = root.path("next");
            if (!next.isTextual() || next.asText().isEmpty()
                    || !next.asText().startsWith("https://www.python.org/")) {
                return;
            }
            root = objectMapper.readTree(client.get(next.asText()));
        }
    }

    /** 请求单版本 windows 元数据；404 或无匹配归档返回 null。 */
    private SdkCatalogEntry fetchWindows(String version, Map<String, Long> releaseDates) throws IOException {
        String url = String.format(WINDOWS_JSON_FORMAT, version, version);
        String body;
        try {
            body = client.get(url);
        } catch (IOException e) {
            return null;
        }
        JsonNode root = objectMapper.readTree(body);
        for (JsonNode item : root.path("versions")) {
            String downloadUrl = item.path("url").asText("");
            if (!downloadUrl.contains("-amd64.zip") || downloadUrl.contains("embed")) {
                continue;
            }
            if (!CatalogUrlPolicy.isAllowed(downloadUrl)) {
                continue;
            }
            String sha256 = item.path("hash").path("sha256").asText("").toLowerCase(Locale.ROOT);
            if (!SHA256_PATTERN.matcher(sha256).matches()) {
                continue;
            }
            SdkCatalogEntry entry = new SdkCatalogEntry();
            entry.setLanguage(LanguageEnum.PYTHON);
            entry.setVersion(version);
            entry.setOs(OsTypeEnum.WINDOWS);
            entry.setArch(ArchEnum.AMD64);
            entry.setDownloadUrl(downloadUrl);
            entry.setSha256(sha256);
            entry.setLts(false);
            entry.setEol(false);
            entry.setLicense(LICENSE);
            entry.setDistribution(DISTRIBUTION);
            entry.setVendor(VENDOR);
            entry.setReleaseTime(releaseDates.get(version));
            return entry;
        }
        return null;
    }

    /** API release_date（"yyyy-MM-dd" 或 ISO-8601）→ epoch 毫秒；缺失或非法返回 null。 */
    private static Long parseReleaseDate(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return text.length() > 10
                    ? Instant.parse(text).toEpochMilli()
                    : LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
