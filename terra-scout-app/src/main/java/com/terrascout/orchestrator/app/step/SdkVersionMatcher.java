package com.terrascout.orchestrator.app.step;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.terrascout.orchestrator.core.domain.SdkVersion;
import com.terrascout.orchestrator.core.dto.SdkInstallItem;
import com.terrascout.orchestrator.core.dto.SdkVersionCandidate;
import com.terrascout.orchestrator.core.enums.CveSeverityEnum;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

/**
 * SDK 版本匹配器。
 *
 * <p>纯静态：输入约束 + 已装版本 + 可用版本，输出推荐 {@link SdkInstallItem}（action =
 * REUSE / INSTALL）。硬过滤 EOL 与 CRITICAL CVE；无匹配区分 422006 / 422007 / 422008。
 *
 * <p>{@link #candidateList} 输出候选表（首项 = 推荐，N≤5）供用户选配，
 * {@link #overrideVersion} 对用户选配版本做同源校验（只许落在候选集内）。
 */
public final class SdkVersionMatcher {

    private static final String UNKNOWN = "UNKNOWN";
    /** 候选表最大数量（首项 = 推荐）。 */
    private static final int MAX_CANDIDATES = 5;
    private static final Pattern LEADING_DIGITS = Pattern.compile("^(\\d+)");
    private static final Pattern LEGACY_CONSTRAINT = Pattern.compile("^1\\.([5-9])$");
    /** 比较符后带空格的写法（">= 20.0.0"），合并为紧凑形式统一处理。 */
    private static final Pattern COMPARATOR_SPACE = Pattern.compile("^(>=|<=|>|<)\\s+");

    private SdkVersionMatcher() {
    }

    /**
     * 匹配单一语言约束并产出推荐项。
     *
     * @param constraint        约束字符串（"17" / "17.0.9" / "[17,18)" / "^18.0.0" / "UNKNOWN"）
     * @param installedVersions 该语言已成功安装的版本集合（可空）
     * @param available         该语言可用版本（已按 os / arch 过滤）
     * @return 推荐安装项（含候选表）
     * @throws TerraScoutException 422006 / 422007 / 422008
     */
    public static SdkInstallItem match(String constraint, Set<String> installedVersions,
                                       List<SdkVersion> available) {
        Set<String> installed = installedVersions == null ? Set.of() : installedVersions;
        List<SdkVersion> resolved = resolvedCandidates(constraint, installed, available);
        SdkVersion best = resolved.get(0);
        boolean reuse = installed.contains(best.getVersion());
        SdkInstallItem item = new SdkInstallItem();
        item.setLanguage(best.getLanguage());
        item.setVersion(best.getVersion());
        item.setAction(reuse ? SdkInstallItem.Action.REUSE : SdkInstallItem.Action.INSTALL);
        item.setEstimatedSizeBytes(best.getSizeBytes());
        item.setReason(reason(best, reuse, constraint));
        item.setCandidates(toCandidateDtos(resolved, installed));
        return item;
    }

    /**
     * 候选版本表：满足约束且通过硬过滤（非 EOL、无 CRITICAL CVE）的可用版本，
     * 按「已装 → LTS → CVE 少 → 版本新 → 发行版」排序，截断至 {@value #MAX_CANDIDATES} 个。
     *
     * @return 候选表（首项 = 自动推荐）；无候选时抛 422006 / 422007 / 422008
     */
    public static List<SdkVersionCandidate> candidateList(String constraint,
                                                          Set<String> installedVersions,
                                                          List<SdkVersion> available) {
        Set<String> installed = installedVersions == null ? Set.of() : installedVersions;
        return toCandidateDtos(resolvedCandidates(constraint, installed, available), installed);
    }

    /**
     * 用户选配版本覆盖：所选版本必须属于可用版本集、通过硬过滤（非 EOL、无
     * CRITICAL CVE）且满足项目约束，否则抛 422006；合法则改写安装项的版本 / 动作 / 体积 / 理由。
     *
     * @param item             自动推荐的安装项（含候选表）
     * @param selectedVersion  用户所选版本
     * @param constraint       该语言的项目约束（与自动推荐同一约束）
     * @param installedVersions 该语言已成功安装的版本集合（可空）
     * @param available        该语言可用版本（已按 os / arch 过滤）
     * @return 改写后的安装项（候选表保持不变，供 UI 回显）
     */
    public static SdkInstallItem overrideVersion(SdkInstallItem item, String selectedVersion,
                                                 String constraint, Set<String> installedVersions,
                                                 List<SdkVersion> available) {
        if (selectedVersion == null || selectedVersion.isBlank()) {
            throw new TerraScoutException(TerraScoutError.NO_SDK_VERSION_MATCH,
                    "用户选配版本为空: " + item.getLanguage());
        }
        SdkVersion selected = null;
        for (SdkVersion version : available == null ? List.<SdkVersion>of() : available) {
            if (version != null && selectedVersion.equals(version.getVersion())) {
                selected = version;
                break;
            }
        }
        if (selected == null || selected.isEol()
                || selected.getHighestCveSeverity() == CveSeverityEnum.CRITICAL) {
            throw new TerraScoutException(TerraScoutError.NO_SDK_VERSION_MATCH,
                    "用户选配版本不可用或已被硬过滤(EOL/高危 CVE): "
                            + item.getLanguage() + " " + selectedVersion);
        }
        if (!matches(selected.getVersion(), normalizeConstraint(constraint))) {
            throw new TerraScoutException(TerraScoutError.NO_SDK_VERSION_MATCH,
                    "用户选配版本不满足项目约束: " + selected.getVersion()
                            + " (约束 " + constraint + ")");
        }
        Set<String> installed = installedVersions == null ? Set.of() : installedVersions;
        boolean reuse = installed.contains(selectedVersion);
        item.setVersion(selectedVersion);
        item.setAction(reuse ? SdkInstallItem.Action.REUSE : SdkInstallItem.Action.INSTALL);
        item.setEstimatedSizeBytes(selected.getSizeBytes());
        item.setReason("根据用户选配版本 " + selectedVersion + " 执行装配");
        return item;
    }

    /**
     * 解析候选集：约束匹配 → 硬过滤（EOL / CRITICAL）→ 排序。无候选时区分 422006/422007/422008。
     */
    private static List<SdkVersion> resolvedCandidates(String constraint, Set<String> installed,
                                                       List<SdkVersion> available) {
        String normalized = normalizeConstraint(constraint);
        List<SdkVersion> matchingRaw = new ArrayList<>();
        List<SdkVersion> candidates = new ArrayList<>();
        for (SdkVersion version : available == null ? List.<SdkVersion>of() : available) {
            if (version == null || version.getVersion() == null) {
                continue;
            }
            if (!matches(version.getVersion(), normalized)) {
                continue;
            }
            matchingRaw.add(version);
            if (!version.isEol() && version.getHighestCveSeverity() != CveSeverityEnum.CRITICAL) {
                candidates.add(version);
            }
        }
        if (candidates.isEmpty()) {
            throw noMatch(constraint, matchingRaw);
        }
        candidates.sort(comparator(installed));
        return candidates;
    }

    /** 候选转 DTO，截断至 {@value #MAX_CANDIDATES} 个。 */
    private static List<SdkVersionCandidate> toCandidateDtos(List<SdkVersion> candidates,
                                                             Set<String> installed) {
        List<SdkVersionCandidate> result = new ArrayList<>();
        for (SdkVersion version : candidates) {
            if (result.size() >= MAX_CANDIDATES) {
                break;
            }
            SdkVersionCandidate candidate = new SdkVersionCandidate();
            candidate.setVersion(version.getVersion());
            candidate.setLts(version.isLts());
            candidate.setEol(version.isEol());
            candidate.setSizeBytes(version.getSizeBytes());
            candidate.setInstalled(installed.contains(version.getVersion()));
            result.add(candidate);
        }
        return result;
    }

    /** 候选为空时区分 422006 / 422007 / 422008。 */
    private static TerraScoutException noMatch(String constraint, List<SdkVersion> matchingRaw) {
        if (matchingRaw.isEmpty()) {
            return new TerraScoutException(TerraScoutError.NO_SDK_VERSION_MATCH,
                    "项目约束无任何 SDK 版本满足: " + constraint);
        }
        boolean allEol = matchingRaw.stream().allMatch(SdkVersion::isEol);
        if (allEol) {
            return new TerraScoutException(TerraScoutError.ONLY_EOL_MATCH,
                    "项目约束只能由 EOL 版本满足: " + constraint);
        }
        return new TerraScoutException(TerraScoutError.ONLY_VULNERABLE_MATCH,
                "项目约束只能由存在高危 CVE 的版本满足: " + constraint);
    }

    /** 排序：已装 → LTS → CVE 少 → 版本新 → 发行版优先（Temurin > Zulu > Corretto > 其他）。 */
    private static Comparator<SdkVersion> comparator(Set<String> installed) {
        return Comparator
                .comparing((SdkVersion v) -> installed.contains(v.getVersion()) ? 0 : 1)
                .thenComparing((SdkVersion v) -> v.isLts() ? 0 : 1)
                .thenComparingInt(SdkVersion::getCveCount)
                .thenComparing((SdkVersion a, SdkVersion b) ->
                        compareVersions(b.getVersion(), a.getVersion()))
                .thenComparingInt(SdkVersionMatcher::distributionRank);
    }

    /** 约束是否匹配版本：UNKNOWN 全匹配；比较符 / 或组合 / 空格 AND / range / caret / x 写法 / 精确含点 / 主版本。 */
    static boolean matches(String version, String constraint) {
        if (constraint == null || constraint.isBlank() || UNKNOWN.equalsIgnoreCase(constraint)) {
            return true;
        }
        String c = constraint.trim();
        // engines.node 常见或组合 "A || B"：任一满足即匹配
        if (c.contains("||")) {
            for (String part : c.split("\\|\\|")) {
                if (!part.isBlank() && matches(version, part.trim())) {
                    return true;
                }
            }
            return false;
        }
        // 比较符带空格（">= 20.0.0"）合并后按紧凑形式继续
        c = COMPARATOR_SPACE.matcher(c).replaceAll("$1");
        // 空格分隔的 AND 组合（">=16 <17"）：全部满足
        if (c.matches(".*\\s+.*")) {
            for (String part : c.split("\\s+")) {
                if (!matches(version, part.trim())) {
                    return false;
                }
            }
            return true;
        }
        // 比较符范围（">=20.0.0" / ">18" / "<=21"）
        if (c.startsWith(">=") || c.startsWith("<=") || c.startsWith(">") || c.startsWith("<")) {
            return comparatorMatches(version, c);
        }
        if (c.startsWith("^")) {
            return sameMajor(version, c.substring(1));
        }
        if (c.startsWith("[") || c.startsWith("(")) {
            return mavenRangeMatches(version, c);
        }
        // "20.x" / "20.X" / "20.*" 写法：主版本匹配
        if (c.matches("\\d+\\.(x|X|\\*)")) {
            return sameMajor(version, c);
        }
        if (c.contains(".")) {
            return version.equals(c) || version.startsWith(c + ".");
        }
        return sameMajor(version, c);
    }

    /**
     * 比较符范围匹配（">=20.0.0" 等）：按主版本比较（与 {@link #mavenRangeMatches}
     * 的 P0 口径一致）；边界无法解析时宽松放行（不阻断匹配流程）。
     */
    static boolean comparatorMatches(String version, String constraint) {
        String c = constraint.trim();
        int consumed = (c.startsWith(">=") || c.startsWith("<=")) ? 2 : 1;
        int bound = majorOf(c.substring(consumed));
        if (bound < 0) {
            return true;
        }
        int major = majorOf(version);
        if (c.startsWith(">=")) {
            return major >= bound;
        }
        if (c.startsWith("<=")) {
            return major <= bound;
        }
        if (c.startsWith(">")) {
            return major > bound;
        }
        return major < bound;
    }

    /** Maven 范围匹配（如 {@code [17,18)}），仅按主版本比较（P0 语义）。 */
    static boolean mavenRangeMatches(String version, String range) {
        boolean leftInclusive = range.startsWith("[");
        boolean rightInclusive = range.endsWith("]");
        String body = range.substring(1, range.length() - 1);
        int comma = body.indexOf(',');
        String left = comma >= 0 ? body.substring(0, comma).trim() : body.trim();
        String right = comma >= 0 ? body.substring(comma + 1).trim() : "";
        int major = majorOf(version);
        if (!left.isEmpty()) {
            int lower = majorOf(left);
            if (leftInclusive ? major < lower : major <= lower) {
                return false;
            }
        }
        if (!right.isEmpty()) {
            int upper = majorOf(right);
            if (rightInclusive ? major > upper : major >= upper) {
                return false;
            }
        }
        return true;
    }

    /** 主版本相等（兼容 Java 历史版本 1.8 → 8）。 */
    static boolean sameMajor(String version, String major) {
        return majorOf(version) == majorOf(major);
    }

    /** 提取主版本号（"17.0.9"→17；"1.8"→8）。 */
    static int majorOf(String version) {
        String value = version == null ? "" : version.trim();
        String[] parts = value.split("\\.");
        if (parts.length >= 2 && "1".equals(parts[0])) {
            Matcher legacy = LEADING_DIGITS.matcher(parts[1]);
            if (legacy.find()) {
                int second = Integer.parseInt(legacy.group());
                if (second >= 5 && second <= 9) {
                    return second;
                }
            }
        }
        Matcher matcher = LEADING_DIGITS.matcher(parts[0]);
        return matcher.find() ? Integer.parseInt(matcher.group()) : -1;
    }

    /** 约束标准化：Java 历史版本 1.8 → 8；空白/空 → UNKNOWN。 */
    static String normalizeConstraint(String constraint) {
        if (constraint == null || constraint.isBlank()) {
            return UNKNOWN;
        }
        String c = constraint.trim();
        Matcher legacy = LEGACY_CONSTRAINT.matcher(c);
        return legacy.matches() ? legacy.group(1) : c;
    }

    /** 语义化版本比较（降序用交换入参）。 */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int x = componentAt(pa, i);
            int y = componentAt(pb, i);
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int componentAt(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        Matcher matcher = LEADING_DIGITS.matcher(parts[index]);
        return matcher.find() ? Integer.parseInt(matcher.group()) : 0;
    }

    private static int distributionRank(SdkVersion version) {
        String distribution = version.getDistribution() == null
                ? "" : version.getDistribution().toLowerCase(Locale.ROOT);
        switch (distribution) {
            case "temurin":
                return 0;
            case "zulu":
                return 1;
            case "corretto":
                return 2;
            default:
                return 3;
        }
    }

    private static String reason(SdkVersion best, boolean reuse, String constraint) {
        if (reuse) {
            return "本机已安装 " + best.getVersion() + "，满足项目约束 " + constraint;
        }
        if (best.isLts()) {
            return best.getVersion() + " 为 LTS 版本，满足项目约束 " + constraint;
        }
        return best.getVersion() + " 为满足项目约束 " + constraint + " 的推荐版本";
    }
}
