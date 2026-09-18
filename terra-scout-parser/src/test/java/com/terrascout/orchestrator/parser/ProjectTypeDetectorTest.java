package com.terrascout.orchestrator.parser;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import com.terrascout.orchestrator.core.enums.ProjectTypeEnum;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 项目类型检测器单测（裁决 R46）：根级声明文件检测 + 「选了上级目录」的子目录候选枚举。
 */
class ProjectTypeDetectorTest {

    @TempDir
    Path tmp;

    @Test
    void detectRecognizesEachRootLevelManifest() throws Exception {
        Path maven = java.nio.file.Files.createDirectory(tmp.resolve("maven"));
        java.nio.file.Files.createFile(maven.resolve("pom.xml"));
        assertThat(ProjectTypeDetector.detect(maven)).isEqualTo(ProjectTypeEnum.MAVEN);

        Path npm = java.nio.file.Files.createDirectory(tmp.resolve("npm"));
        java.nio.file.Files.createFile(npm.resolve("package.json"));
        assertThat(ProjectTypeDetector.detect(npm)).isEqualTo(ProjectTypeEnum.NPM);

        Path mixed = java.nio.file.Files.createDirectory(tmp.resolve("mixed"));
        java.nio.file.Files.createFile(mixed.resolve("pom.xml"));
        java.nio.file.Files.createFile(mixed.resolve("package.json"));
        assertThat(ProjectTypeDetector.detect(mixed)).isEqualTo(ProjectTypeEnum.MIXED);

        Path empty = java.nio.file.Files.createDirectory(tmp.resolve("empty"));
        assertThat(ProjectTypeDetector.detect(empty)).isEqualTo(ProjectTypeEnum.UNKNOWN);
    }

    // ---- 裁决 R48：GO / PYTHON 与多语言组合 ----

    @Test
    void detectRecognizesGoAndPythonManifests() throws Exception {
        Path go1 = java.nio.file.Files.createDirectory(tmp.resolve("go-app"));
        java.nio.file.Files.createFile(go1.resolve("go.mod"));
        assertThat(ProjectTypeDetector.detect(go1)).isEqualTo(ProjectTypeEnum.GO);

        Path pyVersion = java.nio.file.Files.createDirectory(tmp.resolve("py-app"));
        java.nio.file.Files.createFile(pyVersion.resolve(".python-version"));
        assertThat(ProjectTypeDetector.detect(pyVersion)).isEqualTo(ProjectTypeEnum.PYTHON);

        Path pyProject = java.nio.file.Files.createDirectory(tmp.resolve("py-app2"));
        java.nio.file.Files.createFile(pyProject.resolve("pyproject.toml"));
        assertThat(ProjectTypeDetector.detect(pyProject)).isEqualTo(ProjectTypeEnum.PYTHON);

        // .python-version 与 pyproject.toml 并存仍是一种语言
        Path pyBoth = java.nio.file.Files.createDirectory(tmp.resolve("py-app3"));
        java.nio.file.Files.createFile(pyBoth.resolve(".python-version"));
        java.nio.file.Files.createFile(pyBoth.resolve("pyproject.toml"));
        assertThat(ProjectTypeDetector.detect(pyBoth)).isEqualTo(ProjectTypeEnum.PYTHON);
    }

    @Test
    void detectCombinesAnyTwoLanguagesAsMixed() throws Exception {
        Path pomGo = java.nio.file.Files.createDirectory(tmp.resolve("pom-go"));
        java.nio.file.Files.createFile(pomGo.resolve("pom.xml"));
        java.nio.file.Files.createFile(pomGo.resolve("go.mod"));
        assertThat(ProjectTypeDetector.detect(pomGo)).isEqualTo(ProjectTypeEnum.MIXED);

        Path goPy = java.nio.file.Files.createDirectory(tmp.resolve("go-py"));
        java.nio.file.Files.createFile(goPy.resolve("go.mod"));
        java.nio.file.Files.createFile(goPy.resolve("pyproject.toml"));
        assertThat(ProjectTypeDetector.detect(goPy)).isEqualTo(ProjectTypeEnum.MIXED);

        Path nodePy = java.nio.file.Files.createDirectory(tmp.resolve("node-py"));
        java.nio.file.Files.createFile(nodePy.resolve("package.json"));
        java.nio.file.Files.createFile(nodePy.resolve(".python-version"));
        assertThat(ProjectTypeDetector.detect(nodePy)).isEqualTo(ProjectTypeEnum.MIXED);
    }

    @Test
    void findNestedCandidatesIncludesGoAndPythonManifests() throws Exception {
        Path root = java.nio.file.Files.createDirectory(tmp.resolve("nest"));
        Path goApp = java.nio.file.Files.createDirectory(root.resolve("go-app"));
        java.nio.file.Files.createFile(goApp.resolve("go.mod"));
        Path pyApp = java.nio.file.Files.createDirectory(root.resolve("py-app"));
        java.nio.file.Files.createFile(pyApp.resolve("pyproject.toml"));
        java.nio.file.Files.createDirectory(root.resolve("plain"));
        assertThat(ProjectTypeDetector.findNestedCandidates(root))
                .containsExactlyInAnyOrder(goApp, pyApp);
    }

    @Test
    void findNestedCandidatesReturnsEmptyWhenNothingFound() throws Exception {
        Path root = java.nio.file.Files.createDirectory(tmp.resolve("none"));
        java.nio.file.Files.createDirectory(root.resolve("sub-a"));
        assertThat(ProjectTypeDetector.findNestedCandidates(root)).isEmpty();
    }

    @Test
    void findNestedCandidatesFindsSingleChild() throws Exception {
        Path root = java.nio.file.Files.createDirectory(tmp.resolve("single"));
        Path proj = java.nio.file.Files.createDirectory(root.resolve("proj"));
        java.nio.file.Files.createFile(proj.resolve("pom.xml"));
        java.nio.file.Files.createDirectory(root.resolve("plain"));
        assertThat(ProjectTypeDetector.findNestedCandidates(root)).containsExactly(proj);
    }

    @Test
    void findNestedCandidatesListsMultipleChildrenSorted() throws Exception {
        Path root = java.nio.file.Files.createDirectory(tmp.resolve("multi"));
        Path b = java.nio.file.Files.createDirectory(root.resolve("b-app"));
        java.nio.file.Files.createFile(b.resolve("package.json"));
        Path a = java.nio.file.Files.createDirectory(root.resolve("a-app"));
        java.nio.file.Files.createFile(a.resolve("pom.xml"));
        List<String> names = ProjectTypeDetector.findNestedCandidates(root).stream()
                .map(Path::getFileName)
                .map(Path::toString)
                .collect(Collectors.toList());
        assertThat(names).containsExactly("a-app", "b-app");
    }

    @Test
    void findNestedCandidatesSkipsRootLevelManifestDirs() throws Exception {
        Path root = java.nio.file.Files.createDirectory(tmp.resolve("rooted"));
        java.nio.file.Files.createFile(root.resolve("pom.xml"));
        assertThat(ProjectTypeDetector.findNestedCandidates(root)).isEmpty();
    }
}
