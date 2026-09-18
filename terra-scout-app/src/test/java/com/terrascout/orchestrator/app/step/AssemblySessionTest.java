package com.terrascout.orchestrator.app.step;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.terrascout.orchestrator.core.dto.SdkInstallItem;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.ProjectTypeEnum;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配会话一致性单测：跨步骤共享状态读写与失败记录。
 */
class AssemblySessionTest {

    @Test
    void failRecordsCodeAndMessage() {
        AssemblySession session = new AssemblySession();
        session.fail(422006, "boom");
        assertThat(session.getErrorCode()).isEqualTo(422006);
        assertThat(session.getErrorMsg()).isEqualTo("boom");
    }

    @Test
    void defaultsAreSafe() {
        AssemblySession session = new AssemblySession();
        assertThat(session.getConstraints()).isEmpty();
        assertThat(session.getSdkPlan()).isEmpty();
        assertThat(session.getSdkHomes()).isEmpty();
        assertThat(session.getEnv()).isEmpty();
        assertThat(session.getErrorCode()).isNull();
    }

    @Test
    void readWriteRoundTrips() {
        AssemblySession session = new AssemblySession();
        session.setTaskId("t1");
        session.setProjectId("p1");
        session.setProjectRoot(Path.of("C:\\proj"));
        session.setProjectType(ProjectTypeEnum.MAVEN);
        session.setIsolationDir(Path.of("C:\\proj\\.devenv"));
        session.setConstraints(List.of());
        session.setEnv(Map.of("java", "C:\\sdk"));
        session.setSdkHomes(Map.of("java", "C:\\sdk"));
        SdkInstallItem item = new SdkInstallItem();
        item.setVersion("17.0.9");
        session.setSdkPlan(Map.of(LanguageEnum.JAVA, item));

        assertThat(session.getTaskId()).isEqualTo("t1");
        assertThat(session.getProjectId()).isEqualTo("p1");
        assertThat(session.getProjectRoot()).isEqualTo(Path.of("C:\\proj"));
        assertThat(session.getProjectType()).isEqualTo(ProjectTypeEnum.MAVEN);
        assertThat(session.getIsolationDir()).isEqualTo(Path.of("C:\\proj\\.devenv"));
        assertThat(session.getSdkPlan().get(LanguageEnum.JAVA).getVersion()).isEqualTo("17.0.9");
    }
}
