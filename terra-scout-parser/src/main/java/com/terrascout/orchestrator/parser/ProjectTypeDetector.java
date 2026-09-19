package com.terrascout.orchestrator.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import com.terrascout.orchestrator.core.enums.ProjectTypeEnum;

/**
 * 项目类型检测器（P0 项目文件 = pom.xml / package.json；扩展 go.mod /
 * .python-version / pyproject.toml，四语言全链路）。
 *
 * <p>类型判定规则（ProjectTypeEnum）：
 * <ul>
 *   <li>仅 pom.xml → MAVEN；仅 package.json → NPM；仅 go.mod → GO；
 *       仅 .python-version 或 pyproject.toml → PYTHON</li>
 *   <li>2 种及以上语言声明并存 → MIXED（pom.xml、package.json、go.mod、Python 声明各计一种）</li>
 *   <li>无声明文件 → UNKNOWN（上层语义对应 422001）</li>
 * </ul>
 *
 * <p>NODE 版本约束提取顺序：.nvmrc / .node-version 由本检测器仅做存在性参考；
 * package.json 的 engines.node 深度解析由 {@link ConstraintExtractor} 承担。
 */
public final class ProjectTypeDetector {

    /** Maven 项目声明文件名。 */
    public static final String POM_XML = "pom.xml";

    /** npm 项目声明文件名。 */
    public static final String PACKAGE_JSON = "package.json";

    /** Go 项目声明文件名。 */
    public static final String GO_MOD = "go.mod";

    /** Python 版本声明文件（pyenv 约定）。 */
    public static final String PYTHON_VERSION = ".python-version";

    /** Python PEP 621 声明文件。 */
    public static final String PYPROJECT_TOML = "pyproject.toml";

    /** Node 版本声明文件（nvm 约定）。 */
    public static final String NVMRC = ".nvmrc";

    /** Node 版本声明文件（nodenv / fnm 约定）。 */
    public static final String NODE_VERSION = ".node-version";

    private ProjectTypeDetector() {
    }

    /**
     * 检测项目类型。
     *
     * @param projectRoot 项目根目录
     * @return 项目类型；无任何声明文件返回 UNKNOWN（不抛错，422001 由解析编排层语义化）
     * @throws NullPointerException projectRoot 为 null
     */
    public static ProjectTypeEnum detect(Path projectRoot) {
        Objects.requireNonNull(projectRoot, "projectRoot 不能为 null");
        boolean hasPom = Files.isRegularFile(projectRoot.resolve(POM_XML));
        boolean hasPackageJson = Files.isRegularFile(projectRoot.resolve(PACKAGE_JSON));
        boolean hasGoMod = Files.isRegularFile(projectRoot.resolve(GO_MOD));
        boolean hasPython = Files.isRegularFile(projectRoot.resolve(PYTHON_VERSION))
                || Files.isRegularFile(projectRoot.resolve(PYPROJECT_TOML));
        int languageCount = (hasPom ? 1 : 0) + (hasPackageJson ? 1 : 0)
                + (hasGoMod ? 1 : 0) + (hasPython ? 1 : 0);
        if (languageCount >= 2) {
            return ProjectTypeEnum.MIXED;
        }
        if (hasPom) {
            return ProjectTypeEnum.MAVEN;
        }
        if (hasPackageJson) {
            return ProjectTypeEnum.NPM;
        }
        if (hasGoMod) {
            return ProjectTypeEnum.GO;
        }
        if (hasPython) {
            return ProjectTypeEnum.PYTHON;
        }
        return ProjectTypeEnum.UNKNOWN;
    }

    /**
     * 列出所选根的直接子目录中包含声明文件（pom.xml / package.json / go.mod /
     * .python-version / pyproject.toml）的候选。
     *
     * <p>根目录自身无声明文件时用于「选了项目上级目录」的补救：候选恰一个即采纳为项目根，
     * 多个候选因无法判别归属返回全部（上层以 422001 明细列出）；子目录无法枚举时返回空表。
     *
     * @param selectedRoot 用户所选目录（应已校验为存在目录）
     * @return 按路径排序的候选目录（不可变）；无候选或枚举失败返回空表
     */
    public static List<Path> findNestedCandidates(Path selectedRoot) {
        Objects.requireNonNull(selectedRoot, "selectedRoot 不能为 null");
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> children = Files.list(selectedRoot)) {
            children.filter(Files::isDirectory)
                    .sorted()
                    .forEach(child -> {
                        if (Files.isRegularFile(child.resolve(POM_XML))
                                || Files.isRegularFile(child.resolve(PACKAGE_JSON))
                                || Files.isRegularFile(child.resolve(GO_MOD))
                                || Files.isRegularFile(child.resolve(PYTHON_VERSION))
                                || Files.isRegularFile(child.resolve(PYPROJECT_TOML))) {
                            candidates.add(child);
                        }
                    });
        } catch (IOException e) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(candidates);
    }

    /** Python 声明任一存在（供检测器外复用：约束提取按语言分派时的存在性判定）。 */
    static boolean hasPythonManifest(Path projectRoot) {
        return Files.isRegularFile(projectRoot.resolve(PYTHON_VERSION))
                || Files.isRegularFile(projectRoot.resolve(PYPROJECT_TOML));
    }
}
