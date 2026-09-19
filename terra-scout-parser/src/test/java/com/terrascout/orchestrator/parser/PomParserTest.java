package com.terrascout.orchestrator.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;
import com.terrascout.orchestrator.parser.PomParser.ParsedPom;
import com.terrascout.orchestrator.parser.PomParser.VersionSource;

/**
 * {@link PomParser} 单元测试（覆盖全部用例场景）。
 *
 * <p>补充覆盖：版本标准化表、GAV/文件循环、递归超限、parent 坐标不完整、SpringBoot parent 推断、
 * 编译器插件 release 提取、jdk/os profile 激活、pom 缺失 → IAE、properties 不可变。
 */
class PomParserTest {

    @TempDir
    Path projectDir;

    @TempDir
    Path localRepo;

    private Path projectRoot;

    private PomParser parser() {
        return new PomParser(localRepo);
    }

    @BeforeEach
    void setUp() throws IOException {
        projectRoot = projectDir;
        Files.createDirectories(projectRoot);
    }

    // ---- pom.xml 解析用例 ----

    @Test
    void tcPom001LiteralCompilerRelease() throws IOException {
        writeSample("simple.xml");
        ParsedPom pom = parser().parse(projectRoot.resolve("pom.xml"));
        assertThat(pom.javaVersion()).isEqualTo("17");
        assertThat(pom.source()).isEqualTo(VersionSource.COMPILER_RELEASE);
        assertThat(pom.confidence()).isEqualTo(1.0);
        assertThat(pom.javaVersionOrUnknown()).isEqualTo("17");
    }

    @Test
    void tcPom002IndirectReferenceConfidence() throws IOException {
        writeSample("with-properties.xml");
        ParsedPom pom = parser().parse(projectRoot.resolve("pom.xml"));
        assertThat(pom.javaVersion()).isEqualTo("17");
        assertThat(pom.source()).isEqualTo(VersionSource.COMPILER_RELEASE);
        assertThat(pom.confidence()).isEqualTo(0.9);
    }

    @Test
    void tcPom003LegacyJavaVersionNormalized() throws IOException {
        writePom(projectRoot, pom("com.example", "legacy", "1.0.0",
                properties("java.version", "1.8"), null));
        ParsedPom pom = parser().parse(projectRoot.resolve("pom.xml"));
        assertThat(pom.javaVersion()).isEqualTo("8");
        assertThat(pom.source()).isEqualTo(VersionSource.JAVA_VERSION);
    }

    @Test
    void tcPom004ParentFromLocalRepoMerged() throws IOException {
        writeM2("com.example", "parent-pom", "1.0.0", null,
                properties("java.version", "17"));
        writeSample("with-parent.xml");
        ParsedPom pom = parser().parse(projectRoot.resolve("pom.xml"));
        assertThat(pom.javaVersion()).isEqualTo("17");
        assertThat(pom.source()).isEqualTo(VersionSource.JAVA_VERSION);
        // 子省略 groupId/version，继承自父。
        assertThat(pom.groupId()).isEqualTo("com.example");
        assertThat(pom.version()).isEqualTo("1.0.0");
    }

    @Test
    void tcPom005ParentMissingThrows422003() throws IOException {
        writeSample("with-parent.xml");
        assertThatThrownBy(() -> parser().parse(projectRoot.resolve("pom.xml")))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> {
                    TerraScoutException ex = (TerraScoutException) e;
                    assertThat(ex.getError()).isEqualTo(TerraScoutError.PARENT_POM_MISSING);
                });
    }

    @Test
    void tcPom006InvalidXmlThrows422004() throws IOException {
        writeSample("invalid-xml.xml");
        assertThatThrownBy(() -> parser().parse(projectRoot.resolve("pom.xml")))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> {
                    TerraScoutException ex = (TerraScoutException) e;
                    assertThat(ex.getError()).isEqualTo(TerraScoutError.PROPERTY_UNRESOLVED);
                    assertThat(ex.getMessage()).contains("XML");
                });
    }

    @Test
    void tcPom007UndefinedPropertyThrows422004() throws IOException {
        writePom(projectRoot, pom("com.example", "undef", "1.0.0",
                properties("java.version", "${undefined.property}"), null));
        assertThatThrownBy(() -> parser().parse(projectRoot.resolve("pom.xml")))
                .isInstanceOf(TerraScoutException.class)
                .satisfies(e -> {
                    TerraScoutException ex = (TerraScoutException) e;
                    assertThat(ex.getError()).isEqualTo(TerraScoutError.PROPERTY_UNRESOLVED);
                });
    }

    @Test
    void tcPom008MultiModuleRootParsedWithoutFreeze() throws IOException {
        writeSample("with-multi-module.xml");
        ParsedPom pom = parser().parse(projectRoot.resolve("pom.xml"));
        // 多模块不再冻结拒绝；根聚合 POM 自身未声明版本 → UNKNOWN
        assertThat(pom.javaVersionOrUnknown()).isEqualTo("UNKNOWN");
    }

    @Test
    void tcPom008bResolveModulePomsListsDeclaredModules() throws IOException {
        writeSample("with-multi-module.xml");
        Files.createDirectories(projectRoot.resolve("module-a"));
        Files.writeString(projectRoot.resolve("module-a/pom.xml"), "<project/>");
        List<Path> poms = parser().resolveModulePoms(projectRoot.resolve("pom.xml"));
        assertThat(poms).hasSize(1);
        assertThat(poms.get(0).getParent().getFileName().toString()).isEqualTo("module-a");
    }

    @Test
    void tcPom008cResolveModulePomsSkipsMissingModules() throws IOException {
        writeSample("with-multi-module.xml");
        Files.createDirectories(projectRoot.resolve("module-b"));
        Files.writeString(projectRoot.resolve("module-b/pom.xml"), "<project/>");
        // module-a 缺失 → 跳过；仅命中 module-b
        List<Path> poms = parser().resolveModulePoms(projectRoot.resolve("pom.xml"));
        assertThat(poms).hasSize(1);
        assertThat(poms.get(0).getParent().getFileName().toString()).isEqualTo("module-b");
    }

    @Test
    void tcPom009ActiveProfileOverridesMain() throws IOException {
        writeSample("with-profile.xml");
        ParsedPom pom = parser().parse(projectRoot.resolve("pom.xml"));
        // activeByDefault profile 属性 java.version=17 覆盖主 11。
        assertThat(pom.javaVersion()).isEqualTo("17");
    }

    @Test
    void tcPom010InactiveProfileIgnored() throws IOException {
        writeSample("with-profile.xml");
        ParsedPom pom = parser().parse(projectRoot.resolve("pom.xml"));
        // inactive profile 的 java.version=1.4（→4）不得生效。
        assertThat(pom.javaVersion()).isNotEqualTo("4");
    }

    // ---- 补充覆盖 --------------------------------------------------------

    @Test
    void missingJavaVersionIsNullAndUnknown() throws IOException {
        writeSample("missing-java-version.xml");
        ParsedPom pom = parser().parse(projectRoot.resolve("pom.xml"));
        assertThat(pom.javaVersion()).isNull();
        assertThat(pom.source()).isEqualTo(VersionSource.DECLARED_NOWHERE);
        assertThat(pom.javaVersionOrUnknown()).isEqualTo("UNKNOWN");
    }

    @Test
    void versionNormalizationTable() {
        assertThat(PomParser.normalizeVersion("17")).isEqualTo("17");
        assertThat(PomParser.normalizeVersion("17.0")).isEqualTo("17.0");
        assertThat(PomParser.normalizeVersion("1.8")).isEqualTo("8");
        assertThat(PomParser.normalizeVersion("1.7")).isEqualTo("7");
        assertThat(PomParser.normalizeVersion("[17,18)")).isEqualTo("17");
        assertThat(PomParser.normalizeVersion("17.0.9")).isEqualTo("17.0.9");
    }

    @Test
    void fileCycleViam2SelfParentThrows422003() throws IOException {
        writeM2("com.example", "loop", "1.0.0", "com.example:loop:1.0.0", null);
        writePom(projectRoot, pom("com.example", "loop-child", "1.0.0", null,
                parent("com.example", "loop", "1.0.0")));
        assertThatThrownBy(() -> parser().parse(projectRoot.resolve("pom.xml")))
                .isInstanceOf(TerraScoutException.class)
                .extracting(e -> ((TerraScoutException) e).getError())
                .isEqualTo(TerraScoutError.PARENT_POM_MISSING);
    }

    @Test
    void parentDepthExceedsMaxThrows422003() throws IOException {
        // 链 p1→p2→...→p5（p5 声明父 p6，深度超限抛错，p6 文件无需存在）。
        for (int i = 1; i <= 5; i++) {
            String parent = i < 5 ? "com.example:p" + (i + 1) + ":1.0.0" : "com.example:p6:1.0.0";
            writeM2("com.example", "p" + i, "1.0.0", parent, null);
        }
        writePom(projectRoot, pom("com.example", "depth-child", "1.0.0", null,
                parent("com.example", "p1", "1.0.0")));
        assertThatThrownBy(() -> parser().parse(projectRoot.resolve("pom.xml")))
                .isInstanceOf(TerraScoutException.class)
                .extracting(e -> ((TerraScoutException) e).getError())
                .isEqualTo(TerraScoutError.PARENT_POM_MISSING);
    }

    @Test
    void incompleteParentCoordsThrows422003() throws IOException {
        // parent 缺 version。
        writePom(projectRoot, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                + "<modelVersion>4.0.0</modelVersion>"
                + "<parent><groupId>com.example</groupId><artifactId>p</artifactId></parent>"
                + "<artifactId>coord-child</artifactId></project>");
        assertThatThrownBy(() -> parser().parse(projectRoot.resolve("pom.xml")))
                .isInstanceOf(TerraScoutException.class)
                .extracting(e -> ((TerraScoutException) e).getError())
                .isEqualTo(TerraScoutError.PARENT_POM_MISSING);
    }

    @Test
    void springBootParentInferenceFor35Yields17() throws IOException {
        writeM2("org.springframework.boot", "spring-boot-parent", "3.2.5", null, null);
        writePom(projectRoot, pom("com.app", "sb35", "1.0.0", null,
                parent("org.springframework.boot", "spring-boot-parent", "3.2.5")));
        ParsedPom pom = parser().parse(projectRoot.resolve("pom.xml"));
        assertThat(pom.javaVersion()).isEqualTo("17");
        assertThat(pom.source()).isEqualTo(VersionSource.PARENT_INFERENCE);
        assertThat(pom.confidence()).isEqualTo(0.6);
    }

    @Test
    void springBootParentInferenceFor2xYields8() throws IOException {
        writeM2("org.springframework.boot", "spring-boot-parent", "2.7.18", null, null);
        writePom(projectRoot, pom("com.app", "sb27", "1.0.0", null,
                parent("org.springframework.boot", "spring-boot-parent", "2.7.18")));
        ParsedPom pom = parser().parse(projectRoot.resolve("pom.xml"));
        assertThat(pom.javaVersion()).isEqualTo("8");
        assertThat(pom.source()).isEqualTo(VersionSource.PARENT_INFERENCE);
    }

    @Test
    void compilerPluginReleaseExtracted() throws IOException {
        writePom(projectRoot, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                + "<modelVersion>4.0.0</modelVersion>"
                + "<groupId>com.example</groupId><artifactId>plugin-demo</artifactId><version>1.0.0</version>"
                + "<build><plugins><plugin>"
                + "<artifactId>maven-compiler-plugin</artifactId><configuration><release>21</release></configuration>"
                + "</plugin></plugins></build></project>");
        ParsedPom pom = parser().parse(projectRoot.resolve("pom.xml"));
        assertThat(pom.javaVersion()).isEqualTo("21");
        assertThat(pom.source()).isEqualTo(VersionSource.PLUGIN_RELEASE);
    }

    @Test
    void jdkProfileActivationApplied() throws IOException {
        // 激活范围 [8,) 覆盖当前 JVM 主版本，profile 属性覆盖主 java.version。
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                + "<modelVersion>4.0.0</modelVersion>"
                + "<groupId>com.example</groupId><artifactId>jdk-profile</artifactId><version>1.0.0</version>"
                + "<properties><java.version>11</java.version></properties>"
                + "<profiles><profile><id>jdk-act</id>"
                + "<activation><jdk>[8,)</jdk></activation>"
                + "<properties><java.version>18</java.version></properties>"
                + "</profile></profiles></project>";
        writePom(projectRoot, xml);
        ParsedPom pom = parser().parse(projectRoot.resolve("pom.xml"));
        assertThat(pom.javaVersion()).isEqualTo("18");
    }

    @Test
    void osFamilyWindowsProfileActivationApplied() throws IOException {
        assertThatCode(() -> {
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                    + "<modelVersion>4.0.0</modelVersion>"
                    + "<groupId>com.example</groupId><artifactId>os-profile</artifactId><version>1.0.0</version>"
                    + "<properties><java.version>11</java.version></properties>"
                    + "<profiles><profile><id>os-act</id>"
                    + "<activation><os><family>windows</family></os></activation>"
                    + "<properties><java.version>19</java.version></properties>"
                    + "</profile></profiles></project>";
            writePom(projectRoot, xml);
            ParsedPom pom = parser().parse(projectRoot.resolve("pom.xml"));
            // 仅 Windows 环境该 profile 激活（P0 平台冻结），本测试运行于 Windows。
            assertThat(pom.javaVersion()).isEqualTo("19");
        }).doesNotThrowAnyException();
    }

    @Test
    void pomFileNotExistThrowsIae() {
        assertThatThrownBy(() -> parser().parse(projectDir.resolve("pom.xml")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void propertiesAreImmutableCopy() throws IOException {
        writeSample("with-properties.xml");
        ParsedPom pom = parser().parse(projectRoot.resolve("pom.xml"));
        assertThat(pom.properties()).containsKey("java.version");
        assertThatThrownBy(() -> pom.properties().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- 工具 ------------------------------------------------------------

    private static String pom(String group, String artifact, String version, String props, String parent) {
        StringBuilder sb = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                + "<modelVersion>4.0.0</modelVersion>");
        if (parent != null) {
            // 子声明父后，groupId/version 由父继承（读层时合并），子只给 artifactId。
            sb.append(parent).append("<artifactId>").append(artifact).append("</artifactId>");
        } else {
            sb.append("<groupId>").append(group).append("</groupId>")
              .append("<artifactId>").append(artifact).append("</artifactId>")
              .append("<version>").append(version).append("</version>");
        }
        if (props != null) {
            sb.append(props);
        }
        sb.append("</project>");
        return sb.toString();
    }

    private static String properties(String key, String value) {
        return "<properties><" + key + ">" + value + "</" + key + "></properties>";
    }

    private static String parent(String group, String artifact, String version) {
        return "<parent><groupId>" + group + "</groupId><artifactId>" + artifact
                + "</artifactId><version>" + version + "</version></parent>";
    }

    private void writePom(Path root, String content) throws IOException {
        projectRoot = root;
        Files.createDirectories(root);
        Files.writeString(root.resolve("pom.xml"), content);
    }

    private void writeSample(String sample) throws IOException {
        writePom(projectRoot, readSample(sample));
    }

    private static String readSample(String sample) throws IOException {
        try (InputStream in = PomParserTest.class.getResourceAsStream("/pom-samples/" + sample)) {
            if (in == null) {
                throw new IllegalStateException("测试样本缺失: " + sample);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * 在 Maven 本地仓库写入坐标 POM（可选 parent、可选 properties）。
     * 目录/文件名即坐标：{group}/{artifact}/{version}/{artifact}-{version}.pom。
     */
    private void writeM2(String group, String artifact, String version,
                          String parentGavOrNull, String props) throws IOException {
        Path dir = localRepo.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                + "<modelVersion>4.0.0</modelVersion>");
        if (parentGavOrNull != null) {
            String[] parts = parentGavOrNull.split(":");
            sb.append(parent(parts[0], parts[1], parts[2]));
        } else {
            sb.append("<groupId>").append(group).append("</groupId>");
        }
        sb.append("<artifactId>").append(artifact).append("</artifactId>")
          .append("<version>").append(version).append("</version>");
        if (props != null) {
            sb.append(props);
        }
        sb.append("</project>");
        Files.writeString(dir.resolve(artifact + "-" + version + ".pom"), sb.toString());
    }
}
