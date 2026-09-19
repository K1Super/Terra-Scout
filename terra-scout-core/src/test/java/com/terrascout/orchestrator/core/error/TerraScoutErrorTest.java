package com.terrascout.orchestrator.core.error;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TerraScoutError 错误码体系断言。
 *
 * <p>三道防线：46 码总量 + 9 族分布、code 唯一、HTTP 状态码恒等于 code 前 3 位。
 * 枚举手工漂移（增删改码）在本测试立即暴露。
 */
class TerraScoutErrorTest {

    /** 错误码（不含 SUCCESS）应恰为 46 个。 */
    @Test
    @DisplayName("错误码总量 = 46（不含成功码 200000）")
    void errorCountIs46() {
        long errorCount = Stream.of(TerraScoutError.values())
                .filter(e -> e != TerraScoutError.SUCCESS)
                .count();
        assertThat(errorCount).isEqualTo(46);
    }

    /** 全部 code（含 SUCCESS）唯一。 */
    @Test
    @DisplayName("全部 code 唯一")
    void codesAreUnique() {
        Set<Integer> codes = new HashSet<>();
        for (TerraScoutError error : TerraScoutError.values()) {
            assertThat(codes.add(error.getCode()))
                    .as("错误码 %s(%d) 重复", error.name(), error.getCode())
                    .isTrue();
        }
    }

    /** 不变量：HTTP 状态码 == code / 1000；成功码前缀 200，错误码前缀 400-599。 */
    @Test
    @DisplayName("HTTP 状态码恒等于 code 前 3 位")
    void httpStatusMatchesCodePrefix() {
        for (TerraScoutError error : TerraScoutError.values()) {
            assertThat(error.getHttpStatus())
                    .as("%s 的 HTTP 状态", error.name())
                    .isEqualTo(error.getCode() / 1000)
                    .isBetween(200, 599);
        }
    }

    /** 族计数：400:4 / 401:1 / 403:4 / 404:3 / 409:6 / 422:15 / 500:7 / 502:4 / 507:2。 */
    @Test
    @DisplayName("HTTP 族分布与预期一致")
    void familyDistributionMatchesSpec() {
        Map<Integer, Long> actual = Stream.of(TerraScoutError.values())
                .filter(e -> e != TerraScoutError.SUCCESS)
                .collect(Collectors.groupingBy(TerraScoutError::getHttpStatus, TreeMap::new, Collectors.counting()));
        Map<Integer, Long> expected = Map.of(
                400, 4L,
                401, 1L,
                403, 4L,
                404, 3L,
                409, 6L,
                422, 15L,
                500, 7L,
                502, 4L,
                507, 2L);
        assertThat(actual).isEqualTo(expected);
    }

    /** 默认消息非空（SUCCESS 固定 "success"）。 */
    @Test
    @DisplayName("默认消息非空且与成功语义一致")
    void defaultMessageIsNotBlank() {
        for (TerraScoutError error : TerraScoutError.values()) {
            assertThat(error.getDefaultMessage())
                    .as("%s 的默认消息", error.name())
                    .isNotBlank();
        }
        assertThat(TerraScoutError.SUCCESS.getDefaultMessage()).isEqualTo("success");
        assertThat(TerraScoutError.SUCCESS.getCode()).isEqualTo(200000);
    }

    /** 关键语义锚点。 */
    @Test
    @DisplayName("关键错误码语义锚点（401001 / 409005 / 409006 / 422010）")
    void semanticAnchors() {
        assertThat(TerraScoutError.TOKEN_INVALID.getCode()).isEqualTo(401001);
        assertThat(TerraScoutError.TOKEN_INVALID.getHttpStatus()).isEqualTo(401);
        assertThat(TerraScoutError.TASK_TERMINAL_STATE.getCode()).isEqualTo(409005);
        assertThat(TerraScoutError.TASK_QUEUE_FULL.getCode()).isEqualTo(409006);
        assertThat(TerraScoutError.ARCHIVE_UNSAFE.getCode()).isEqualTo(422010);
        assertThat(TerraScoutError.COMMAND_REJECTED.getCode()).isEqualTo(403002);
        assertThat(TerraScoutError.SDK_SOURCE_UNREACHABLE.getCode()).isEqualTo(502001);
        assertThat(TerraScoutError.DOWNLOAD_DISK_FULL.getCode()).isEqualTo(507001);
    }
}
