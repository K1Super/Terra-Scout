package com.terrascout.orchestrator.app.step;

import java.util.List;
import java.util.Set;

import com.terrascout.orchestrator.core.domain.SdkVersion;
import com.terrascout.orchestrator.core.dto.SdkInstallItem;
import com.terrascout.orchestrator.core.dto.SdkVersionCandidate;
import com.terrascout.orchestrator.core.enums.CveSeverityEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.error.TerraScoutError;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK 版本匹配器单测。
 */
class SdkVersionMatcherTest {

    private static SdkVersion sdk(String version, boolean eol, boolean lts) {
        return sdk(version, eol, lts, CveSeverityEnum.NONE, 0, "temurin");
    }

    private static SdkVersion sdk(String version, boolean eol, boolean lts,
                                  CveSeverityEnum severity, int cveCount, String distribution) {
        SdkVersion v = new SdkVersion();
        v.setLanguage(LanguageEnum.JAVA);
        v.setVersion(version);
        v.setEol(eol);
        v.setLts(lts);
        v.setHighestCveSeverity(severity);
        v.setCveCount(cveCount);
        v.setDistribution(distribution);
        v.setSizeBytes(100L);
        return v;
    }

    @Test
    void matchReusesInstalledVersion() {
        SdkInstallItem item = SdkVersionMatcher.match(
                "17", Set.of("17.0.9"), List.of(sdk("17.0.9", false, true)));
        assertThat(item.getAction()).isEqualTo(SdkInstallItem.Action.REUSE);
        assertThat(item.getVersion()).isEqualTo("17.0.9");
    }

    @Test
    void matchPrefersLtsWhenInstalling() {
        SdkInstallItem item = SdkVersionMatcher.match(
                "17", Set.of(), List.of(sdk("17.0.10", false, false), sdk("17.0.9", false, true)));
        assertThat(item.getAction()).isEqualTo(SdkInstallItem.Action.INSTALL);
        assertThat(item.getVersion()).isEqualTo("17.0.9");
    }

    @Test
    void matchThrowsNoMatchWhenNoCandidate() {
        assertThatThrownBy(() -> SdkVersionMatcher.match("99", Set.of(), List.of()))
                .extracting(e -> ((com.terrascout.orchestrator.core.error.TerraScoutException) e).getError())
                .isEqualTo(TerraScoutError.NO_SDK_VERSION_MATCH);
    }

    @Test
    void matchThrowsOnlyEolWhenAllEol() {
        assertThatThrownBy(() -> SdkVersionMatcher.match(
                "17", Set.of(), List.of(sdk("17.0.9", true, true))))
                .extracting(e -> ((com.terrascout.orchestrator.core.error.TerraScoutException) e).getError())
                .isEqualTo(TerraScoutError.ONLY_EOL_MATCH);
    }

    @Test
    void matchThrowsOnlyVulnerableWhenCritical() {
        assertThatThrownBy(() -> SdkVersionMatcher.match(
                "17", Set.of(), List.of(sdk("17.0.9", false, true, CveSeverityEnum.CRITICAL, 3, "temurin"))))
                .extracting(e -> ((com.terrascout.orchestrator.core.error.TerraScoutException) e).getError())
                .isEqualTo(TerraScoutError.ONLY_VULNERABLE_MATCH);
    }

    @Test
    void normalizeConstraintHandlesLegacyAndBlank() {
        assertThat(SdkVersionMatcher.normalizeConstraint(null)).isEqualTo("UNKNOWN");
        assertThat(SdkVersionMatcher.normalizeConstraint("   ")).isEqualTo("UNKNOWN");
        assertThat(SdkVersionMatcher.normalizeConstraint("1.8")).isEqualTo("8");
        assertThat(SdkVersionMatcher.normalizeConstraint("17")).isEqualTo("17");
    }

    @Test
    void matchesCoversAllConstraintForms() {
        assertThat(SdkVersionMatcher.matches("17.0.9", "UNKNOWN")).isTrue();
        assertThat(SdkVersionMatcher.matches("17.0.9", "17")).isTrue();
        assertThat(SdkVersionMatcher.matches("17.0.9", "17.0")).isTrue();
        assertThat(SdkVersionMatcher.matches("17.0.9", "17.0.9")).isTrue();
        assertThat(SdkVersionMatcher.matches("18.0.0", "^18.0.0")).isTrue();
        assertThat(SdkVersionMatcher.matches("17.0.9", "16")).isFalse();
    }

    @Test
    void mavenRangeMatchesByMajor() {
        assertThat(SdkVersionMatcher.mavenRangeMatches("17.0.9", "[17,18)")).isTrue();
        assertThat(SdkVersionMatcher.mavenRangeMatches("16.0.1", "[17,18)")).isFalse();
        assertThat(SdkVersionMatcher.mavenRangeMatches("18.0.0", "[17,18)")).isFalse();
        assertThat(SdkVersionMatcher.mavenRangeMatches("18.0.0", "[17,18]")).isTrue();
        assertThat(SdkVersionMatcher.mavenRangeMatches("17.0.0", "(17,18)")).isFalse();
    }

    // ---- engines.node 范围表达式 ----

    @Test
    void comparatorRangesMatchByMajor() {
        assertThat(SdkVersionMatcher.matches("20.11.0", ">=20.0.0")).isTrue();
        assertThat(SdkVersionMatcher.matches("19.9.0", ">=20.0.0")).isFalse();
        assertThat(SdkVersionMatcher.matches("18.5.0", ">18")).isFalse();
        assertThat(SdkVersionMatcher.matches("19.0.0", ">18")).isTrue();
        assertThat(SdkVersionMatcher.matches("18.0.0", "<=18")).isTrue();
        assertThat(SdkVersionMatcher.matches("19.0.0", "<=18")).isFalse();
        assertThat(SdkVersionMatcher.matches("18.0.0", "<18")).isFalse();
        assertThat(SdkVersionMatcher.matches("17.9.0", "<18")).isTrue();
        // 比较符后带空格（engines 官方允许写法）
        assertThat(SdkVersionMatcher.matches("20.11.0", ">= 20.0.0")).isTrue();
        assertThat(SdkVersionMatcher.matches("19.9.0", ">= 20.0.0")).isFalse();
    }

    @Test
    void orCombinationMatchesWhenAnyBranchSatisfied() {
        assertThat(SdkVersionMatcher.matches("18.20.0", "^18 || >=20")).isTrue();
        assertThat(SdkVersionMatcher.matches("20.11.0", "^18 || >=20")).isTrue();
        assertThat(SdkVersionMatcher.matches("16.0.0", "^18 || >=20")).isFalse();
    }

    @Test
    void andCombinationMatchesWhenAllBranchesSatisfied() {
        assertThat(SdkVersionMatcher.matches("20.11.0", ">=16 <21")).isTrue();
        assertThat(SdkVersionMatcher.matches("22.1.0", ">=16 <21")).isFalse();
        assertThat(SdkVersionMatcher.matches("15.0.0", ">=16 <21")).isFalse();
    }

    @Test
    void xWildcardMatchesByMajor() {
        assertThat(SdkVersionMatcher.matches("20.11.0", "20.x")).isTrue();
        assertThat(SdkVersionMatcher.matches("21.0.0", "20.x")).isFalse();
        assertThat(SdkVersionMatcher.matches("20.0.0", "20.*")).isTrue();
    }

    @Test
    void comparatorMatchPicksLtsWithinRange() {
        SdkInstallItem item = SdkVersionMatcher.match(">=20.0.0", Set.of(),
                List.of(sdk("18.19.0", false, false), sdk("20.11.0", false, true)));
        assertThat(item.getAction()).isEqualTo(SdkInstallItem.Action.INSTALL);
        assertThat(item.getVersion()).isEqualTo("20.11.0");
    }

    @Test
    void unrecognizableComparatorBoundFallsThroughLeniently() {
        assertThat(SdkVersionMatcher.comparatorMatches("20.0.0", ">=abc")).isTrue();
    }

    @Test
    void majorOfHandlesLegacyAndPlain() {
        assertThat(SdkVersionMatcher.majorOf("17.0.9")).isEqualTo(17);
        assertThat(SdkVersionMatcher.majorOf("1.8.0")).isEqualTo(8);
        assertThat(SdkVersionMatcher.majorOf("1.7")).isEqualTo(7);
        assertThat(SdkVersionMatcher.majorOf("jar")).isEqualTo(-1);
    }

    @Test
    void compareVersionsOrdersLexically() {
        assertThat(SdkVersionMatcher.compareVersions("17.0.9", "17.0.10")).isNegative();
        assertThat(SdkVersionMatcher.compareVersions("17.1.0", "17.0.10")).isPositive();
        assertThat(SdkVersionMatcher.compareVersions("17", "17.0.0")).isZero();
    }

    // ---- 候选表与用户选配 ----

    @Test
    void matchIncludesSortedCandidateList() {
        SdkInstallItem item = SdkVersionMatcher.match(
                "17", Set.of("17.0.11"),
                List.of(
                        sdk("17.0.9", false, false),
                        sdk("17.0.10", false, true),
                        sdk("17.0.11", false, true)));
        assertThat(item.getCandidates()).hasSize(3);
        // 已装优先 → 17.0.11 为首项推荐
        assertThat(item.getCandidates().get(0).getVersion()).isEqualTo("17.0.11");
        assertThat(item.getCandidates().get(0).isInstalled()).isTrue();
        assertThat(item.getCandidates().get(0).isLts()).isTrue();
        assertThat(item.getCandidates().get(0).isEol()).isFalse();
        assertThat(item.getCandidates().get(0).getSizeBytes()).isEqualTo(100L);
    }

    @Test
    void candidateListTruncatesToFive() {
        List<SdkVersionCandidate> candidates = SdkVersionMatcher.candidateList(
                "20", Set.of(),
                List.of(
                        sdk("20.0.1", false, false),
                        sdk("20.0.2", false, false),
                        sdk("20.0.3", false, false),
                        sdk("20.0.4", false, false),
                        sdk("20.0.5", false, false),
                        sdk("20.0.6", false, false),
                        sdk("20.0.7", false, true)));
        assertThat(candidates).hasSize(5);
        // 排序：LTS 最优先（唯一 LTS 且无已装）
        assertThat(candidates.get(0).getVersion()).isEqualTo("20.0.7");
        assertThat(candidates.get(0).isLts()).isTrue();
    }

    @Test
    void candidateListFiltersEolAndCritical() {
        List<SdkVersionCandidate> candidates = SdkVersionMatcher.candidateList(
                "17", Set.of(),
                List.of(
                        sdk("17.0.9", true, true),
                        sdk("17.0.10", false, false, CveSeverityEnum.CRITICAL, 3, "temurin"),
                        sdk("17.0.11", false, true)));
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getVersion()).isEqualTo("17.0.11");
    }

    @Test
    void candidateListThrowsNoMatchWhenEmpty() {
        assertThatThrownBy(() -> SdkVersionMatcher.candidateList("99", Set.of(), List.of()))
                .extracting(e -> ((com.terrascout.orchestrator.core.error.TerraScoutException) e).getError())
                .isEqualTo(TerraScoutError.NO_SDK_VERSION_MATCH);
    }

    @Test
    void overrideVersionAppliesSelection() {
        SdkInstallItem item = SdkVersionMatcher.match(
                "17", Set.of(),
                List.of(sdk("17.0.9", false, false), sdk("17.0.10", false, true)));
        assertThat(item.getVersion()).isEqualTo("17.0.10");

        SdkInstallItem overridden = SdkVersionMatcher.overrideVersion(
                item, "17.0.9", "17", Set.of(),
                List.of(sdk("17.0.9", false, false), sdk("17.0.10", false, true)));
        assertThat(overridden.getVersion()).isEqualTo("17.0.9");
        assertThat(overridden.getAction()).isEqualTo(SdkInstallItem.Action.INSTALL);
        assertThat(overridden.getEstimatedSizeBytes()).isEqualTo(100L);
        assertThat(overridden.getReason()).contains("用户选配版本 17.0.9");
        // 候选表保持不变
        assertThat(overridden.getCandidates()).hasSize(2);
        assertThat(overridden.getCandidates().get(0).getVersion()).isEqualTo("17.0.10");
    }

    @Test
    void overrideVersionMarksReuseWhenInstalled() {
        SdkInstallItem item = SdkVersionMatcher.match(
                "17", Set.of(),
                List.of(sdk("17.0.9", false, false), sdk("17.0.10", false, true)));
        SdkInstallItem overridden = SdkVersionMatcher.overrideVersion(
                item, "17.0.9", "17", Set.of("17.0.9"),
                List.of(sdk("17.0.9", false, false), sdk("17.0.10", false, true)));
        assertThat(overridden.getAction()).isEqualTo(SdkInstallItem.Action.REUSE);
    }

    @Test
    void overrideVersionRejectsUnknownVersion() {
        SdkInstallItem item = SdkVersionMatcher.match(
                "17", Set.of(), List.of(sdk("17.0.9", false, true)));
        assertThatThrownBy(() -> SdkVersionMatcher.overrideVersion(
                item, "17.0.99", "17", Set.of(), List.of(sdk("17.0.9", false, true))))
                .extracting(e -> ((com.terrascout.orchestrator.core.error.TerraScoutException) e).getError())
                .isEqualTo(TerraScoutError.NO_SDK_VERSION_MATCH);
    }

    @Test
    void overrideVersionRejectsEolOrCriticalSelection() {
        SdkInstallItem item = SdkVersionMatcher.match(
                "17", Set.of(), List.of(sdk("17.0.9", false, true)));
        assertThatThrownBy(() -> SdkVersionMatcher.overrideVersion(
                item, "17.0.9", "17", Set.of(), List.of(sdk("17.0.9", true, true))))
                .extracting(e -> ((com.terrascout.orchestrator.core.error.TerraScoutException) e).getError())
                .isEqualTo(TerraScoutError.NO_SDK_VERSION_MATCH);
    }

    @Test
    void overrideVersionRejectsConstraintViolation() {
        SdkInstallItem item = SdkVersionMatcher.match(
                "17", Set.of(), List.of(sdk("17.0.9", false, true), sdk("16.0.1", false, false)));
        assertThatThrownBy(() -> SdkVersionMatcher.overrideVersion(
                item, "16.0.1", "17", Set.of(),
                List.of(sdk("17.0.9", false, true), sdk("16.0.1", false, false))))
                .extracting(e -> ((com.terrascout.orchestrator.core.error.TerraScoutException) e).getError())
                .isEqualTo(TerraScoutError.NO_SDK_VERSION_MATCH);
    }

    @Test
    void overrideVersionRejectsBlankVersion() {
        SdkInstallItem item = SdkVersionMatcher.match(
                "17", Set.of(), List.of(sdk("17.0.9", false, true)));
        assertThatThrownBy(() -> SdkVersionMatcher.overrideVersion(
                item, "  ", "17", Set.of(), List.of(sdk("17.0.9", false, true))))
                .extracting(e -> ((com.terrascout.orchestrator.core.error.TerraScoutException) e).getError())
                .isEqualTo(TerraScoutError.NO_SDK_VERSION_MATCH);
    }
}
