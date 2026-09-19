package com.terrascout.orchestrator.core.error;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TerraScoutException 行为断言。
 */
class TerraScoutExceptionTest {

    @Test
    @DisplayName("单参构造：消息取默认文案，无明细")
    void constructorWithErrorCodeOnly() {
        TerraScoutException exception = new TerraScoutException(TerraScoutError.PARENT_POM_MISSING);

        assertThat(exception.getError()).isEqualTo(TerraScoutError.PARENT_POM_MISSING);
        assertThat(exception.getMessage()).isEqualTo(TerraScoutError.PARENT_POM_MISSING.getDefaultMessage());
        assertThat(exception.getDetails()).isNull();
        assertThat(exception.getHttpStatus()).isEqualTo(422);
    }

    @Test
    @DisplayName("自定义消息构造：保留细节消息")
    void constructorWithCustomMessage() {
        TerraScoutException exception = new TerraScoutException(TerraScoutError.UNKNOWN, "boom");

        assertThat(exception.getMessage()).isEqualTo("boom");
        assertThat(exception.getError().getCode()).isEqualTo(500007);
    }

    @Test
    @DisplayName("明细构造：details 拷贝后只读")
    void constructorWithDetailsIsUnmodifiable() {
        Map<String, Object> mutable = new LinkedHashMap<>();
        mutable.put("path", "D:\\proj");

        TerraScoutException exception = new TerraScoutException(TerraScoutError.PROJECT_PATH_NOT_FOUND, mutable);
        Map<String, Object> details = exception.getDetails();

        assertThat(details).containsEntry("path", "D:\\proj");
        // 源 map 后续修改不影响快照（防御性拷贝）
        mutable.put("path", "changed");
        assertThat(details).containsEntry("path", "D:\\proj");
        // 只读视图
        assertThatThrownBy(() -> details.put("k", "v"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("null 明细构造：details 保持 null")
    void constructorWithNullDetails() {
        TerraScoutException exception = new TerraScoutException(TerraScoutError.TASK_NOT_FOUND, (Map<String, Object>) null);
        assertThat(exception.getDetails()).isNull();
    }

    @Test
    @DisplayName("原因构造：保留 cause 链（系统异常包装模式）")
    void constructorWithCause() {
        IOExceptionLike cause = new IOExceptionLike("disk gone");
        TerraScoutException exception =
                new TerraScoutException(TerraScoutError.UNKNOWN, cause.getMessage(), cause);

        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(exception.getHttpStatus()).isEqualTo(500);
    }

    /** 测试用受检异常替身。 */
    private static final class IOExceptionLike extends Exception {
        private static final long serialVersionUID = 1L;

        IOExceptionLike(String message) {
            super(message);
        }
    }
}
