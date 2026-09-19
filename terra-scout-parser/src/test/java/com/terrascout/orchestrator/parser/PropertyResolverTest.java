package com.terrascout.orchestrator.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

/**
 * {@link PropertyResolver} 单元测试。
 *
 * <p>覆盖：字面量、单级/链式引用、循环引用（含自引用）、未定义引用、空表。
 */
class PropertyResolverTest {

    @Test
    void literalValuePassThrough() {
        Map<String, String> resolved = PropertyResolver.resolve(map("maven.compiler.release", "17"));
        assertThat(resolved).containsEntry("maven.compiler.release", "17");
    }

    @Test
    void singleLevelReferenceResolved() {
        Map<String, String> resolved = PropertyResolver.resolve(map(
                "java.version", "17",
                "maven.compiler.release", "${java.version}"));
        assertThat(resolved.get("maven.compiler.release")).isEqualTo("17");
    }

    @Test
    void chainedReferenceResolvedInFixedRounds() {
        // 3 轮依赖：c → b → a，MAX_ROUNDS=10 足够收敛。
        Map<String, String> resolved = PropertyResolver.resolve(map(
                "a", "18",
                "b", "${a}.0",
                "c", "${b}"));
        assertThat(resolved.get("c")).isEqualTo("18.0");
    }

    @Test
    void unknownReferenceThrowsPropertyUnresolved() {
        assertThatThrownBy(() -> PropertyResolver.resolve(map("a", "${undefined}")))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> {
                    TerraScoutException ex = (TerraScoutException) e;
                    assertThat(ex.getError()).isEqualTo(TerraScoutError.PROPERTY_UNRESOLVED);
                });
    }

    @Test
    void circularReferenceThrowsPropertyUnresolved() {
        assertThatThrownBy(() -> PropertyResolver.resolve(map(
                "a", "${b}",
                "b", "${a}")))
                .isInstanceOf(TerraScoutException.class)
                .extracting(e -> ((TerraScoutException) e).getError())
                .isEqualTo(TerraScoutError.PROPERTY_UNRESOLVED);
    }

    @Test
    void selfReferenceThrowsPropertyUnresolved() {
        assertThatThrownBy(() -> PropertyResolver.resolve(map("a", "${a}")))
                .isInstanceOf(TerraScoutException.class)
                .extracting(e -> ((TerraScoutException) e).getError())
                .isEqualTo(TerraScoutError.PROPERTY_UNRESOLVED);
    }

    @Test
    void emptyMapReturnsEmpty() {
        assertThat(PropertyResolver.resolve(Map.of())).isEmpty();
    }

    private static Map<String, String> map(String k1, String v1, String... more) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put(k1, v1);
        for (int i = 0; i + 1 < more.length; i += 2) {
            result.put(more[i], more[i + 1]);
        }
        return result;
    }
}
