package com.terrascout.orchestrator.app.service.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SDK 版本号数字段比较器。
 *
 * <p>忽略前缀与后缀中的非数字噪声（如 {@code v22.20.0}、{@code go1.22.4}、
 * {@code 17.0.9+1}），仅按数字段逐段数值比较；段数不足按 0 补齐；
 * 数字段全等时回退字典序保证全序稳定（排序器可确定性工作）。
 */
public final class VersionComparator {

    /** 抽取版本号内的全部数字段。 */
    private static final Pattern NUMERIC_SEGMENT = Pattern.compile("\\d+");

    private VersionComparator() {
    }

    /**
     * 比较两个版本号：返回负数/零/正数，含义同 {@link Comparable#compareTo(Object)}。
     *
     * @param left  左版本号（如 "17.0.9"、"v22.20.0"）
     * @param right 右版本号
     * @return 数字段逐段比较结果；数字段全等时按字符串字典序兜底
     */
    public static int compare(String left, String right) {
        List<Long> leftSegments = segments(left);
        List<Long> rightSegments = segments(right);
        int size = Math.max(leftSegments.size(), rightSegments.size());
        for (int i = 0; i < size; i++) {
            long leftPart = i < leftSegments.size() ? leftSegments.get(i) : 0L;
            long rightPart = i < rightSegments.size() ? rightSegments.get(i) : 0L;
            if (leftPart != rightPart) {
                return Long.compare(leftPart, rightPart);
            }
        }
        return left.compareTo(right);
    }

    /**
     * 版本号首个数字段（主版本 major）：如 {@code "17.0.9"} → 17、{@code "1.8.0_504"} → 1。
     * 无任何数字时返回 0。
     *
     * @param version 版本号
     * @return 主版本号（可能为 0）
     */
    public static long major(String version) {
        List<Long> segments = segments(version);
        return segments.isEmpty() ? 0L : segments.get(0);
    }

    /** 抽取数字段序列：如 "17.0.20.1" → [17, 0, 20, 1]。无数字时为空列表。 */
    private static List<Long> segments(String version) {
        List<Long> result = new ArrayList<>();
        Matcher matcher = NUMERIC_SEGMENT.matcher(version);
        while (matcher.find()) {
            result.add(Long.parseLong(matcher.group()));
        }
        return result;
    }
}
