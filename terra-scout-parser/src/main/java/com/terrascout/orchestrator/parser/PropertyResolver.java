package com.terrascout.orchestrator.parser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

/**
 * Maven 属性占位符求值器（pom-parser-algorithm.md 3.3）。
 *
 * <p>输入为已合并的原始属性表（含父 POM 属性、激活 profile 属性、project.* 内置属性与
 * 系统 java.version 回退，均由调用方 {@link PomParser} 注入），输出全部求值后的属性表。
 *
 * <p>求值循环语义：
 * <ul>
 *   <li>每轮基于上一轮快照对每个 value 做 {@code ${key} → map.get(key)} 替换</li>
 *   <li>一轮无任何变化即退出（自引用 / 死引用在此收敛）</li>
 *   <li>最多 {@value #MAX_ROUNDS} 轮</li>
 *   <li>循环结束后仍含未解析占位符（含循环引用、未定义引用）→ 抛 422004</li>
 * </ul>
 */
public final class PropertyResolver {

    /** 求值循环上限（算法 3.3 步骤 4）。 */
    public static final int MAX_ROUNDS = 10;

    /** 占位符语法：${key}（不支持嵌套占位符，P0 范围外）。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private PropertyResolver() {
    }

    /**
     * 求值属性表。
     *
     * @param rawProperties 已合并的原始属性表（key/value 均非 null）
     * @return 求值后的属性表（与输入顺序一致的独立副本）
     * @throws TerraScoutException 422004：存在无法求值的占位符（未定义引用或循环引用）
     */
    public static Map<String, String> resolve(Map<String, String> rawProperties) {
        Map<String, String> current = new LinkedHashMap<>(rawProperties);
        for (int round = 0; round < MAX_ROUNDS; round++) {
            Map<String, String> next = new LinkedHashMap<>();
            boolean changed = false;
            for (Map.Entry<String, String> entry : current.entrySet()) {
                String replaced = replacePlaceholders(entry.getValue(), current);
                next.put(entry.getKey(), replaced);
                changed |= !replaced.equals(entry.getValue());
            }
            current = next;
            if (!changed) {
                break;
            }
        }
        assertFullyResolved(current);
        return current;
    }

    /**
     * 单值占位符替换：仅替换 map 中已存在的 key，未知占位符保留原样（交由终检判定）。
     */
    private static String replacePlaceholders(String value, Map<String, String> lookup) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        StringBuilder result = new StringBuilder(value.length());
        while (matcher.find()) {
            String replacement = lookup.get(matcher.group(1));
            matcher.appendReplacement(result,
                    replacement == null ? Matcher.quoteReplacement(matcher.group()) : Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * 终检：任何属性值仍含 ${...} 占位符即无法求值，抛 422004。
     */
    private static void assertFullyResolved(Map<String, String> properties) {
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            Matcher matcher = PLACEHOLDER.matcher(entry.getValue());
            if (matcher.find()) {
                throw new TerraScoutException(TerraScoutError.PROPERTY_UNRESOLVED,
                        String.format("属性 %s 无法求值：%s", entry.getKey(), entry.getValue()));
            }
        }
    }
}
