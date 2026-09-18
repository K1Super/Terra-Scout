package com.terrascout.orchestrator.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.terrascout.orchestrator.core.dto.ProjectConstraint;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

/**
 * {@link ConstraintExtractor} 单元测试（rest-schema 3.4.1；PomParser 契约见 progress 4.3~4.4）。
 *
 * <p>覆盖：MAVEN / NPM / MIXED / UNKNOWN(→422001)、.nvmrc 去前导 v、.nvmrc 优先于 .node-version、
 * 无 Node 声明 → UNKNOWN、null 入参 → NPE。
 */
class ConstraintExtractorTest {

    @TempDir
    Path tempDir;

    private Path projectDir;

    private final ConstraintExtractor extractor = new ConstraintExtractor();

    @BeforeEach
    void setUp() throws IOException {
        projectDir = tempDir.resolve("proj");
        Files.createDirectories(projectDir);
    }

    @Test
    void mavenProjectYieldsJavaConstraint() throws IOException {
        copySampleTo("simple.xml", "pom.xml");
        List<ProjectConstraint> constraints = extractor.extract(projectDir);
        assertThat(constraints).hasSize(1);
        ProjectConstraint c = constraints.get(0);
        assertThat(c.getLanguage()).isEqualTo(LanguageEnum.JAVA);
        assertThat(c.getConstraint()).isEqualTo("17");
        assertThat(c.getSourceFile()).isEqualTo("pom.xml");
        assertThat(c.getConfidence()).isEqualTo(1.0);
    }

    @Test
    void multiModuleAggregatesAndDeduplicatesModuleConstraints() throws IOException {
        writeAggregator("root", "a", "b");
        writeModule("a", "17");
        writeModule("b", "17");
        List<ProjectConstraint> constraints = extractor.extract(projectDir);
        assertThat(constraints).hasSize(1);
        assertThat(constraints.get(0).getLanguage()).isEqualTo(LanguageEnum.JAVA);
        assertThat(constraints.get(0).getConstraint()).isEqualTo("17");
        assertThat(constraints.get(0).getSourceFile()).isEqualTo("a/pom.xml");
        assertThat(constraints.get(0).getConfidence()).isEqualTo(1.0);
    }

    @Test
    void multiModuleDistinctVersionsKeptInDeclarationOrder() throws IOException {
        writeAggregator("root", "a", "b");
        writeModule("a", "17");
        writeModule("b", "21");
        List<ProjectConstraint> constraints = extractor.extract(projectDir);
        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).getConstraint()).isEqualTo("17");
        assertThat(constraints.get(0).getSourceFile()).isEqualTo("a/pom.xml");
        assertThat(constraints.get(1).getConstraint()).isEqualTo("21");
        assertThat(constraints.get(1).getSourceFile()).isEqualTo("b/pom.xml");
    }

    @Test
    void multiModuleRootDeclarationWinsDedupe() throws IOException {
        Files.writeString(projectDir.resolve("pom.xml"), withModules("17", "a"));
        writeModule("a", "17");
        List<ProjectConstraint> constraints = extractor.extract(projectDir);
        assertThat(constraints).hasSize(1);
        assertThat(constraints.get(0).getConstraint()).isEqualTo("17");
        assertThat(constraints.get(0).getSourceFile()).isEqualTo("pom.xml");
    }

    @Test
    void multiModuleAllUndeclaredFallsBackToUnknown() throws IOException {
        writeAggregator("root", "a");
        writeModule("a", null);
        List<ProjectConstraint> constraints = extractor.extract(projectDir);
        assertThat(constraints).hasSize(1);
        assertThat(constraints.get(0).getConstraint()).isEqualTo("UNKNOWN");
        assertThat(constraints.get(0).getSourceFile()).isEqualTo("pom.xml");
    }

    @Test
    void multiModuleNestedAggregatorTraversed() throws IOException {
        writeAggregator("root", "outer");
        Files.createDirectories(projectDir.resolve("outer"));
        Files.writeString(projectDir.resolve("outer/pom.xml"), withModules(null, "inner"));
        Files.createDirectories(projectDir.resolve("outer/inner"));
        Files.writeString(projectDir.resolve("outer/inner/pom.xml"), modulePom("11"));
        List<ProjectConstraint> constraints = extractor.extract(projectDir);
        assertThat(constraints).hasSize(1);
        assertThat(constraints.get(0).getConstraint()).isEqualTo("11");
        assertThat(constraints.get(0).getSourceFile()).isEqualTo("outer/inner/pom.xml");
    }

    @Test
    void multiModuleMissingModulePomIsSkipped() throws IOException {
        writeAggregator("root", "ghost", "a");
        writeModule("a", "17");
        List<ProjectConstraint> constraints = extractor.extract(projectDir);
        assertThat(constraints).hasSize(1);
        assertThat(constraints.get(0).getConstraint()).isEqualTo("17");
        assertThat(constraints.get(0).getSourceFile()).isEqualTo("a/pom.xml");
    }

    @Test
    void npmProjectWithNvmrcYieldsNodeConstraint() throws IOException {
        Files.writeString(projectDir.resolve("package.json"), "{}");
        Files.writeString(projectDir.resolve(".nvmrc"), "v18.20.0\n");
        List<ProjectConstraint> constraints = extractor.extract(projectDir);
        assertThat(constraints).hasSize(1);
        ProjectConstraint c = constraints.get(0);
        assertThat(c.getLanguage()).isEqualTo(LanguageEnum.NODE);
        assertThat(c.getConstraint()).isEqualTo("18.20.0");
        assertThat(c.getSourceFile()).isEqualTo(".nvmrc");
        assertThat(c.getConfidence()).isEqualTo(1.0);
    }

    @Test
    void nvmrcTakesPriorityOverNodeVersionFile() throws IOException {
        Files.writeString(projectDir.resolve("package.json"), "{}");
        Files.writeString(projectDir.resolve(".nvmrc"), "18.20.0\n");
        Files.writeString(projectDir.resolve(".node-version"), "20.0.0\n");
        List<ProjectConstraint> constraints = extractor.extract(projectDir);
        assertThat(constraints.get(0).getConstraint()).isEqualTo("18.20.0");
        assertThat(constraints.get(0).getSourceFile()).isEqualTo(".nvmrc");
    }

    @Test
    void nodeVersionFileUsedWhenNoNvmrc() throws IOException {
        Files.writeString(projectDir.resolve("package.json"), "{}");
        Files.writeString(projectDir.resolve(".node-version"), "v16.14.0\n");
        List<ProjectConstraint> constraints = extractor.extract(projectDir);
        assertThat(constraints.get(0).getConstraint()).isEqualTo("16.14.0");
        assertThat(constraints.get(0).getSourceFile()).isEqualTo(".node-version");
    }

    @Test
    void npmProjectWithoutNodeVersionIsUnknown() throws IOException {
        Files.writeString(projectDir.resolve("package.json"), "{}");
        List<ProjectConstraint> constraints = extractor.extract(projectDir);
        assertThat(constraints).hasSize(1);
        assertThat(constraints.get(0).getLanguage()).isEqualTo(LanguageEnum.NODE);
        assertThat(constraints.get(0).getConstraint()).isEqualTo("UNKNOWN");
    }

    // ---- 裁决 R47：package.json engines.node ----

    @Test
    void npmProjectEnginesNodeYieldsRangeConstraint() throws IOException {
        Files.writeString(projectDir.resolve("package.json"),
                "{\"name\":\"weblog\",\"engines\":{\"node\":\">=20.0.0\"}}");
        List<ProjectConstraint> constraints = extractor.extract(projectDir);
        assertThat(constraints).hasSize(1);
        ProjectConstraint c = constraints.get(0);
        assertThat(c.getLanguage()).isEqualTo(LanguageEnum.NODE);
        assertThat(c.getConstraint()).isEqualTo(">=20.0.0");
        assertThat(c.getSourceFile()).isEqualTo("package.json");
        assertThat(c.getConfidence()).isEqualTo(1.0);
    }

    @Test
    void nvmrcTakesPriorityOverEnginesNode() throws IOException {
        Files.writeString(projectDir.resolve("package.json"),
                "{\"engines\":{\"node\":\">=20.0.0\"}}");
        Files.writeString(projectDir.resolve(".nvmrc"), "18.20.0\n");
        List<ProjectConstraint> constraints = extractor.extract(projectDir);
        assertThat(constraints.get(0).getConstraint()).isEqualTo("18.20.0");
        assertThat(constraints.get(0).getSourceFile()).isEqualTo(".nvmrc");
    }

    @Test
    void nodeVersionFileTakesPriorityOverEnginesNode() throws IOException {
        Files.writeString(projectDir.resolve("package.json"),
                "{\"engines\":{\"node\":\">=20.0.0\"}}");
        Files.writeString(projectDir.resolve(".node-version"), "v16.14.0\n");
        List<ProjectConstraint> constraints = extractor.extract(projectDir);
        assertThat(constraints.get(0).getConstraint()).isEqualTo("16.14.0");
        assertThat(constraints.get(0).getSourceFile()).isEqualTo(".node-version");
    }

    @Test
    void invalidPackageJsonEnginesTreatedAsUndeclared() throws IOException {
        Files.writeString(projectDir.resolve("package.json"), "{not-json");
        List<ProjectConstraint> constraints = extractor.extract(projectDir);
        assertThat(constraints).hasSize(1);
        assertThat(constraints.get(0).getConstraint()).isEqualTo("UNKNOWN");
    }

    @Test
    void enginesNodeNonTextualOrBlankTreatedAsUndeclared() throws IOException {
        Files.writeString(projectDir.resolve("package.json"),
                "{\"engines\":{\"node\":42}}");
        assertThat(extractor.extract(projectDir).get(0).getConstraint()).isEqualTo("UNKNOWN");
        Files.writeString(projectDir.resolve("package.json"),
                "{\"engines\":{\"node\":\"   \"}}");
        assertThat(extractor.extract(projectDir).get(0).getConstraint()).isEqualTo("UNKNOWN");
        Files.writeString(projectDir.resolve("package.json"), "{\"engines\":{\"npm\":\"9\"}}");
        assertThat(extractor.extract(projectDir).get(0).getConstraint()).isEqualTo("UNKNOWN");
    }

    @Test
    void mixedProjectEnginesNodeYieldsJavaThenNode() throws IOException {
        copySampleTo("simple.xml", "pom.xml");
        Files.writeString(projectDir.resolve("package.json"),
                "{\"engines\":{\"node\":\"^20.0.0\"}}");
        List<ProjectConstraint> constraints = extractor.extract(projectDir);
        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).getLanguage()).isEqualTo(LanguageEnum.JAVA);
        assertThat(constraints.get(1).getLanguage()).isEqualTo(LanguageEnum.NODE);
        assertThat(constraints.get(1).getConstraint()).isEqualTo("^20.0.0");
    }

    @Test
    void mixedProjectYieldsJavaThenNode() throws IOException {
        copySampleTo("simple.xml", "pom.xml");
        Files.writeString(projectDir.resolve("package.json"), "{}");
        Files.writeString(projectDir.resolve(".node-version"), "18.20.0\n");
        List<ProjectConstraint> constraints = extractor.extract(projectDir);
        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).getLanguage()).isEqualTo(LanguageEnum.JAVA);
        assertThat(constraints.get(1).getLanguage()).isEqualTo(LanguageEnum.NODE);
    }

    @Test
    void unknownProjectThrowsProjectTypeUnknown() {
        assertThatThrownBy(() -> extractor.extract(projectDir))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> {
                    TerraScoutException ex = (TerraScoutException) e;
                    assertThat(ex.getError()).isEqualTo(TerraScoutError.PROJECT_TYPE_UNKNOWN);
                });
    }

    // ---- 裁决 R48：GO / PYTHON 约束提取 ----

    @Test
    void goProjectYieldsGoConstraintNormalizedAsRange() throws IOException {
        Files.writeString(projectDir.resolve("go.mod"),
                "module example.com/app\n\ngo 1.21\n\nrequire golang.org/x/text v0.14.0\n");
        List<ProjectConstraint> constraints = extractor.extract(projectDir);
        assertThat(constraints).hasSize(1);
        ProjectConstraint c = constraints.get(0);
        assertThat(c.getLanguage()).isEqualTo(LanguageEnum.GO);
        assertThat(c.getConstraint()).isEqualTo(">=1.21");
        assertThat(c.getSourceFile()).isEqualTo("go.mod");
        assertThat(c.getConfidence()).isEqualTo(1.0);
    }

    @Test
    void goDirectiveThreePartVersionAndTrailingCommentTolerated() throws IOException {
        Files.writeString(projectDir.resolve("go.mod"),
                "module example.com/app\n\n\tgo 1.21.5 // minimum toolchain\n");
        ProjectConstraint c = extractor.extract(projectDir).get(0);
        assertThat(c.getLanguage()).isEqualTo(LanguageEnum.GO);
        assertThat(c.getConstraint()).isEqualTo(">=1.21.5");
    }

    @Test
    void goModWithoutGoDirectiveFallsBackToUnknown() throws IOException {
        Files.writeString(projectDir.resolve("go.mod"),
                "module example.com/app\n\nrequire golang.org/x/text v0.14.0\n");
        ProjectConstraint c = extractor.extract(projectDir).get(0);
        assertThat(c.getLanguage()).isEqualTo(LanguageEnum.GO);
        assertThat(c.getConstraint()).isEqualTo("UNKNOWN");
        assertThat(c.getSourceFile()).isEqualTo("go.mod");
    }

    @Test
    void pythonProjectPythonVersionFileYieldsConstraint() throws IOException {
        Files.writeString(projectDir.resolve(".python-version"), "3.11.9\n");
        List<ProjectConstraint> constraints = extractor.extract(projectDir);
        assertThat(constraints).hasSize(1);
        ProjectConstraint c = constraints.get(0);
        assertThat(c.getLanguage()).isEqualTo(LanguageEnum.PYTHON);
        assertThat(c.getConstraint()).isEqualTo("3.11.9");
        assertThat(c.getSourceFile()).isEqualTo(".python-version");
        assertThat(c.getConfidence()).isEqualTo(1.0);
    }

    @Test
    void pythonVersionFileWithUtf8BomStripsBom() throws IOException {
        Files.writeString(projectDir.resolve(".python-version"), "\uFEFF3.11.9\n");
        ProjectConstraint c = extractor.extract(projectDir).get(0);
        assertThat(c.getConstraint()).isEqualTo("3.11.9");
        assertThat(c.getSourceFile()).isEqualTo(".python-version");
    }

    @Test
    void nodeVersionFileWithUtf8BomStripsBom() throws IOException {
        Files.writeString(projectDir.resolve("package.json"), "{}");
        Files.writeString(projectDir.resolve(".nvmrc"), "\uFEFF20.11.0\n");
        ProjectConstraint c = extractor.extract(projectDir).get(0);
        assertThat(c.getLanguage()).isEqualTo(LanguageEnum.NODE);
        assertThat(c.getConstraint()).isEqualTo("20.11.0");
    }

    @Test
    void pythonVersionFileTakesPriorityOverRequiresPython() throws IOException {
        Files.writeString(projectDir.resolve(".python-version"), "3.11.9\n");
        Files.writeString(projectDir.resolve("pyproject.toml"),
                "[project]\nrequires-python = \">=3.9\"\n");
        ProjectConstraint c = extractor.extract(projectDir).get(0);
        assertThat(c.getConstraint()).isEqualTo("3.11.9");
        assertThat(c.getSourceFile()).isEqualTo(".python-version");
    }

    @Test
    void pyprojectRequiresPythonYieldsRangeConstraint() throws IOException {
        Files.writeString(projectDir.resolve("pyproject.toml"),
                "[build-system]\nrequires = [\"hatchling\"]\n\n[project]\n"
                        + "name = \"demo\"\nrequires-python = \">=3.9\"\n");
        ProjectConstraint c = extractor.extract(projectDir).get(0);
        assertThat(c.getLanguage()).isEqualTo(LanguageEnum.PYTHON);
        assertThat(c.getConstraint()).isEqualTo(">=3.9");
        assertThat(c.getSourceFile()).isEqualTo("pyproject.toml");
    }

    @Test
    void requiresPythonOutsideProjectSectionIgnored() throws IOException {
        Files.writeString(projectDir.resolve("pyproject.toml"),
                "[tool.something]\nrequires-python = \">=3.9\"\n\n[project]\nname = \"demo\"\n");
        ProjectConstraint c = extractor.extract(projectDir).get(0);
        assertThat(c.getConstraint()).isEqualTo("UNKNOWN");
        assertThat(c.getSourceFile()).isEqualTo(".python-version");
    }

    @Test
    void mixedProjectYieldsJavaNodeGoPythonInOrder() throws IOException {
        copySampleTo("simple.xml", "pom.xml");
        Files.writeString(projectDir.resolve("package.json"), "{}");
        Files.writeString(projectDir.resolve(".node-version"), "18.20.0\n");
        Files.writeString(projectDir.resolve("go.mod"), "module x\n\ngo 1.21\n");
        Files.writeString(projectDir.resolve(".python-version"), "3.11.9\n");
        List<ProjectConstraint> constraints = extractor.extract(projectDir);
        assertThat(constraints).hasSize(4);
        assertThat(constraints.get(0).getLanguage()).isEqualTo(LanguageEnum.JAVA);
        assertThat(constraints.get(1).getLanguage()).isEqualTo(LanguageEnum.NODE);
        assertThat(constraints.get(2).getLanguage()).isEqualTo(LanguageEnum.GO);
        assertThat(constraints.get(3).getLanguage()).isEqualTo(LanguageEnum.PYTHON);
    }

    @Test
    void mixedPomGoWithoutNodeYieldsOnlyJavaAndGo() throws IOException {
        copySampleTo("simple.xml", "pom.xml");
        Files.writeString(projectDir.resolve("go.mod"), "module x\n\ngo 1.21\n");
        List<ProjectConstraint> constraints = extractor.extract(projectDir);
        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).getLanguage()).isEqualTo(LanguageEnum.JAVA);
        assertThat(constraints.get(1).getLanguage()).isEqualTo(LanguageEnum.GO);
    }

    @Test
    void nullRootThrowsNpe() {
        assertThatThrownBy(() -> extractor.extract(null))
                .isInstanceOf(NullPointerException.class);
    }

    /** 写入聚合根 pom（javaVersion 可空）。 */
    private void writeAggregator(String artifactId, String... modules) throws IOException {
        Files.writeString(projectDir.resolve("pom.xml"), withModules(null, modules));
    }

    /** 写入单模块 pom（javaVersion 可空表示未声明）。 */
    private void writeModule(String module, String javaVersion) throws IOException {
        Path dir = projectDir.resolve(module);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), modulePom(javaVersion));
    }

    /** 聚合 POM 文案。 */
    private static String withModules(String javaVersion, String... modules) {
        StringBuilder sb = new StringBuilder();
        sb.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>")
                .append("<groupId>com.e2e</groupId><artifactId>agg</artifactId><version>1.0.0</version>")
                .append("<properties>");
        if (javaVersion != null) {
            sb.append("<java.version>").append(javaVersion).append("</java.version>");
        }
        sb.append("</properties><modules>");
        for (String module : modules) {
            sb.append("<module>").append(module).append("</module>");
        }
        return sb.append("</modules></project>").toString();
    }

    /** 单模块 POM 文案。 */
    private static String modulePom(String javaVersion) {
        StringBuilder sb = new StringBuilder();
        sb.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>")
                .append("<groupId>com.e2e</groupId><artifactId>mod</artifactId><version>1.0.0</version>")
                .append("<properties>");
        if (javaVersion != null) {
            sb.append("<java.version>").append(javaVersion).append("</java.version>");
        }
        return sb.append("</properties></project>").toString();
    }

    private void copySampleTo(String sample, String targetName) throws IOException {
        try (InputStream in = ConstraintExtractorTest.class.getResourceAsStream("/pom-samples/" + sample)) {
            if (in == null) {
                throw new IllegalStateException("测试样本缺失: " + sample);
            }
            Files.copy(in, projectDir.resolve(targetName));
        }
    }
}
