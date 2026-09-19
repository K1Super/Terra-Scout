package com.terrascout.orchestrator.app.service.catalog;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.terrascout.orchestrator.core.enums.ArchEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.OsTypeEnum;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Node.js 官方源抓取器（官方站 + 淘宝镜像兜底）。
 *
 * <p>数据源：{@code https://nodejs.org/dist/index.json}；官方站不可达时回退
 * {@code https://registry.npmmirror.com/-/binary/node/index.json}（同构 JSON）。
 * 每个版本从同源 SHASUMS256.txt 提取 win-x64.zip 的真实 sha256（不信任列表页，
 * 与 Downloader 校验链路闭环）。
 *
 * <p>版本线策略：版本按 major 归组为「版本线」，行级支持性按官方
 * EOL 静态表裁决（官方发布计划的既有历史事实；index.json 不含 end 字段，不能
 * 从数据内取）：行内存在 LTS 条目时——表内 major 按 EOL 日期裁决（今天晚于 EOL
 * 即排除，如 18/20），大于表上界的未来 major 视为受支持线，表上界以下且不在
 * 表内的远古 major（6/8/10 等）排除；无 LTS 条目时仅当为比所有 LTS 行更新的
 * 偶数 major Current 行才纳入（奇数 non-LTS 行排除）。每条受支持线按版本倒序
 * 至多探测 {@value #PROBE_PER_LINE} 个、最多保留 {@value #KEEP_PER_LINE} 个
 * （SHASUMS 缺失顺延）——覆盖完整受支持版本线，而非仅最新窗口。
 */
@Component
public class NodeJsFetcher implements LanguageCatalogFetcher {

    public static final String PRIMARY_INDEX = "https://nodejs.org/dist/index.json";

    public static final String PRIMARY_BASE = "https://nodejs.org/dist";

    public static final String MIRROR_INDEX = "https://registry.npmmirror.com/-/binary/node/index.json";

    public static final String MIRROR_BASE = "https://registry.npmmirror.com/-/binary/node";

    /** 每条版本线最多探测的版本数（SHASUMS 缺失时顺延）。 */
    public static final int PROBE_PER_LINE = 4;

    /** 每条版本线最多保留的成功条目数（取最新稳定 patch）。 */
    public static final int KEEP_PER_LINE = 2;

    /**
     * 官方发布计划的行级 EOL 静态表（major → 维护终止日）。index.json 不含 end
     * 字段（实测全量无该字段），故行级支持性不能在数据内裁决，只能以官方既定
     * 时间表为准；Clock 注入使测试固定「今天」后本表裁决完全确定。
     */
    private static final Map<Long, LocalDate> LINE_EOL = Map.of(
            12L, LocalDate.of(2022, 4, 30),
            14L, LocalDate.of(2023, 4, 30),
            16L, LocalDate.of(2023, 9, 11),
            18L, LocalDate.of(2025, 4, 30),
            20L, LocalDate.of(2026, 4, 30),
            22L, LocalDate.of(2027, 4, 30),
            24L, LocalDate.of(2028, 4, 30),
            26L, LocalDate.of(2029, 4, 30));

    /** EOL 表上界；表外更大的 major 行视为未来受支持线。 */
    private static final long MAX_TABLE_MAJOR = 26;

    private static final String LICENSE = "MIT";
    private static final String DISTRIBUTION = "Node.js";
    private static final String VENDOR = "OpenJS Foundation";

    private final CatalogHttpClient client;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public NodeJsFetcher(CatalogHttpClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, Clock.systemUTC());
    }

    /** 测试注入固定时钟，以确定性裁决行级 EOL（生产走系统 UTC 时钟）。 */
    public NodeJsFetcher(CatalogHttpClient client, ObjectMapper objectMapper, Clock clock) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public List<SdkCatalogEntry> fetch() throws Exception {
        String indexBody;
        String base;
        try {
            indexBody = client.get(PRIMARY_INDEX);
            base = PRIMARY_BASE;
        } catch (IOException primaryFailure) {
            // 官方站不可达 → 镜像兜底；镜像再失败则由编排层记该语言抓取失败
            indexBody = client.get(MIRROR_INDEX);
            base = MIRROR_BASE;
        }
        JsonNode releases = objectMapper.readTree(indexBody);
        if (!releases.isArray()) {
            return List.of();
        }
        List<JsonNode> sorted = new ArrayList<>();
        releases.forEach(sorted::add);
        sorted.sort(this::compareRelease);
        Map<Long, List<JsonNode>> lines = new TreeMap<>(Comparator.reverseOrder());
        long maxLtsMajor = Long.MIN_VALUE;
        for (JsonNode release : sorted) {
            String version = stripV(release.path("version").asText(""));
            if (version.isEmpty()) {
                continue;
            }
            long major = VersionComparator.major(version);
            lines.computeIfAbsent(major, key -> new ArrayList<>()).add(release);
            if (isLts(release)) {
                maxLtsMajor = Math.max(maxLtsMajor, major);
            }
        }
        LocalDate today = LocalDate.now(clock);
        List<SdkCatalogEntry> result = new ArrayList<>();
        for (Map.Entry<Long, List<JsonNode>> line : lines.entrySet()) {
            if (!isSupportedLine(line.getValue(), line.getKey(), maxLtsMajor, today)) {
                continue;
            }
            int kept = 0;
            int probed = 0;
            for (JsonNode release : line.getValue()) {
                if (kept >= KEEP_PER_LINE || probed >= PROBE_PER_LINE) {
                    break;
                }
                probed++;
                SdkCatalogEntry entry = parseRelease(release, base);
                if (entry != null) {
                    result.add(entry);
                    kept++;
                }
            }
        }
        return result;
    }

    /**
     * 行级受支持裁决（静态表口径）：行内存在 LTS 条目时——major 大于表上界视为
     * 未来受支持线；表内 major 按 EOL 日期裁决（今天晚于 EOL 日期才排除）；表上界
     * 以下且不在表内的远古 major 排除。无 LTS 条目时仅当为比所有 LTS 行更新的
     * 偶数 major Current 行才纳入（奇数 non-LTS 行排除）。
     */
    private static boolean isSupportedLine(List<JsonNode> lineReleases, long major,
                                           long maxLtsMajor, LocalDate today) {
        boolean hasLts = false;
        for (JsonNode release : lineReleases) {
            if (isLts(release)) {
                hasLts = true;
                break;
            }
        }
        if (hasLts) {
            if (major > MAX_TABLE_MAJOR) {
                // 表外更大的 major → 未来受支持线（表上界随时间推移扩展）
                return true;
            }
            LocalDate eol = LINE_EOL.get(major);
            return eol != null && !today.isAfter(eol);
        }
        return maxLtsMajor != Long.MIN_VALUE && major % 2 == 0 && major > maxLtsMajor;
    }

    /** 排序：LTS 优先 → 版本号数字段倒序。 */
    private int compareRelease(JsonNode left, JsonNode right) {
        boolean leftLts = isLts(left);
        boolean rightLts = isLts(right);
        if (leftLts != rightLts) {
            return leftLts ? -1 : 1;
        }
        String leftVersion = stripV(left.path("version").asText(""));
        String rightVersion = stripV(right.path("version").asText(""));
        // 倒序：右侧在前视为「更大」
        return VersionComparator.compare(rightVersion, leftVersion);
    }

    /** lts 字段：字符串代号（"Fermium" 等）视为 LTS，boolean false 或缺失视为非 LTS。 */
    private static boolean isLts(JsonNode release) {
        JsonNode lts = release.path("lts");
        if (lts.isTextual()) {
            return !lts.asText().isEmpty();
        }
        return lts.isBoolean() && lts.asBoolean();
    }

    /** 解析单个版本条目；sha256 不在 SHASUMS 中或格式不符则跳过（返回 null）。 */
    private SdkCatalogEntry parseRelease(JsonNode release, String base) throws IOException {
        String rawVersion = release.path("version").asText("");
        if (rawVersion.isEmpty() || !rawVersion.startsWith("v")) {
            return null;
        }
        String version = rawVersion.substring(1);
        String shasums;
        try {
            shasums = client.get(base + "/" + rawVersion + "/SHASUMS256.txt");
        } catch (IOException e) {
            // 单版本校验文件缺失不影响其他版本
            return null;
        }
        // 官方产物名形如 node-v{version}-win-x64.zip：SHASUMS 行与下载 URL 均保留 v 前缀
        Pattern linePattern = Pattern.compile(
                "([a-f0-9]{64})\\s+node-" + Pattern.quote(rawVersion) + "-win-x64\\.zip");
        Matcher matcher = linePattern.matcher(shasums.toLowerCase(Locale.ROOT));
        if (!matcher.find()) {
            return null;
        }
        SdkCatalogEntry entry = new SdkCatalogEntry();
        entry.setLanguage(LanguageEnum.NODE);
        entry.setVersion(version);
        entry.setOs(OsTypeEnum.WINDOWS);
        entry.setArch(ArchEnum.AMD64);
        entry.setDownloadUrl(base + "/" + rawVersion + "/node-" + rawVersion + "-win-x64.zip");
        entry.setSha256(matcher.group(1));
        entry.setLts(isLts(release));
        entry.setEol(false);
        entry.setLicense(LICENSE);
        entry.setDistribution(DISTRIBUTION);
        entry.setVendor(VENDOR);
        entry.setReleaseTime(parseDate(release.path("date").asText("")));
        return entry;
    }

    private static String stripV(String version) {
        return version.startsWith("v") ? version.substring(1) : version;
    }

    /** 发行日期（yyyy-MM-dd）→ UTC 零点 epoch 毫秒；缺失或非法返回 null。 */
    private static Long parseDate(String date) {
        if (date == null || date.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
