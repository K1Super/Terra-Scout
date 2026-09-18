package com.terrascout.orchestrator.app.unit;

import com.terrascout.orchestrator.app.service.catalog.VersionComparator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 版本号数字段比较器单测：数字段逐段比较 / 前缀剥离 / 段数补齐 / 字典序兜底。
 */
class VersionComparatorTest {

    @Test
    void comparesByNumericSegments() {
        assertThat(VersionComparator.compare("17.0.9", "17.0.10") < 0).isTrue();
        assertThat(VersionComparator.compare("21.0.12.1", "17.0.20.1") > 0).isTrue();
        assertThat(VersionComparator.compare("1.22.4", "1.22.4") == 0).isTrue();
    }

    @Test
    void ignoresNonNumericPrefixAndSuffix() {
        assertThat(VersionComparator.compare("v22.20.0", "v20.19.5") > 0).isTrue();
        assertThat(VersionComparator.compare("go1.22.4", "go1.22.3") > 0).isTrue();
        assertThat(VersionComparator.compare("17.0.9+1", "17.0.9") > 0).isTrue();
    }

    @Test
    void padsMissingSegmentsWithZero() {
        assertThat(VersionComparator.compare("1.10", "1.10.1") < 0).isTrue();
        assertThat(VersionComparator.compare("1.10.1", "1.10") > 0).isTrue();
        // 段补齐后数字相等 → 回退字典序："1.10.0" 比 "1.10" 多后缀故更大
        assertThat(VersionComparator.compare("1.10.0", "1.10") > 0).isTrue();
    }

    @Test
    void fallsBackToLexicographicWhenSegmentsEqual() {
        assertThat(VersionComparator.compare("1.0.0-x", "1.0.0-y") < 0).isTrue();
    }
}
