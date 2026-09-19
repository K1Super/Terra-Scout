package com.terrascout.orchestrator.parser;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.terrascout.orchestrator.core.dto.ProjectConstraint;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.ProjectTypeEnum;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

/**
 * 项目版本约束提取器（数据模型见 {@link ProjectConstraint}）。
 *
 * <p>依据 {@link ProjectTypeDetector} 判定的项目类型提取版本约束：
 * <ul>
 *   <li>MAVEN → 委托 {@link PomParser} 解析 pom.xml 聚合树（含 {@code <modules>} 子模块递归合并），
     *       产出 JAVA 约束（constraint 已标准化；相同版本去重，根优先）</li>
     *   <li>NPM → 读 .nvmrc（优先）/ .node-version / package.json engines.node，
     *       产出 NODE 约束（去前导 v、首行 trim；engines 保留范围表达式原样供匹配器语义化）</li>
     *   <li>GO → 读 go.mod 的 {@code go} 指令，产出 GO 约束（最低版本语义，值归一为
     *       {@code >=<version>}，如 {@code >=1.21}）</li>
     *   <li>PYTHON → 读 .python-version（优先）/ pyproject.toml {@code [project] requires-python}，
     *       产出 PYTHON 约束（原样保留供匹配器语义化）</li>
 *   <li>MIXED → 按声明文件实际存在性依次产出 JAVA → NODE → GO → PYTHON 约束</li>
 *   <li>UNKNOWN → 抛 422001（PROJECT_TYPE_UNKNOWN），提示无可识别声明文件</li>
 * </ul>
 *
 * <p>约束顺序保证：MAVEN → [JAVA…]；NPM → [NODE]；GO → [GO]；PYTHON → [PYTHON]；
 * MIXED → [JAVA…, NODE, GO, PYTHON]（截取实际存在的语言）。
 */
public final class ConstraintExtractor {

    /** 未声明任何版本的约束字面量（与 PomParser.Unused javaVersion 语义一致）。 */
    private static final String UNKNOWN = "UNKNOWN";

    /** 多模块遍历深度上限（聚合 → 子模块），防异常深层嵌套。 */
    private static final int MAX_MODULE_DEPTH = 5;

    /** 约束来源文件：根 pom.xml。 */
    private static final String SOURCE_POM = ProjectTypeDetector.POM_XML;

    /** go.mod 的 go 指令：行首 `go 1.21` / `go 1.21.5`，容忍缩进与行尾注释。 */
    private static final java.util.regex.Pattern GO_DIRECTIVE =
            java.util.regex.Pattern.compile("^\\s*go\\s+(\\d+\\.\\d+(?:\\.\\d+)?)");

    /** pyproject.toml [project] 段 requires-python 双引号字符串值。 */
    private static final java.util.regex.Pattern REQUIRES_PYTHON =
            java.util.regex.Pattern.compile("^\\s*requires-python\\s*=\\s*\"([^\"]+)\"");

    /** JSON 解析器（package.json engines.node；线程安全）。 */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /** 解析器日志（System.Logger，与 PomParser 约定一致）。 */
    private static final System.Logger LOG = System.getLogger(ConstraintExtractor.class.getName());

    /** pom 解析器（默认本地仓库，测试可注入隔离仓库）。 */
    private final PomParser pomParser;

    /** 使用默认本地仓库 {@code %USERPROFILE%/.m2/repository}。 */
    public ConstraintExtractor() {
        this(new PomParser());
    }

    /**
     * 注入 pom 解析器（用于测试隔离本地仓库与构造）。
     *
     * @param pomParser pom 解析器
     */
    public ConstraintExtractor(PomParser pomParser) {
        this.pomParser = Objects.requireNonNull(pomParser, "pomParser 不能为 null");
    }

    /**
     * 提取项目全部版本约束。
     *
     * @param projectRoot 项目根目录
     * @return 约束列表（顺序见类说明）
     * @throws TerraScoutException 422001 项目类型无法识别；422003 父 POM 缺失/递归超限/循环
     * @throws NullPointerException projectRoot 为 null
     */
    public List<ProjectConstraint> extract(Path projectRoot) {
        Objects.requireNonNull(projectRoot, "projectRoot 不能为 null");
        ProjectTypeEnum type = ProjectTypeDetector.detect(projectRoot);
        if (type == ProjectTypeEnum.UNKNOWN) {
            throw new TerraScoutException(TerraScoutError.PROJECT_TYPE_UNKNOWN,
                    "无可识别项目声明文件（pom.xml / package.json / go.mod / pyproject.toml），"
                            + "无法确定项目类型");
        }
        List<ProjectConstraint> constraints = new ArrayList<>(4);
        boolean hasPom = Files.isRegularFile(projectRoot.resolve(ProjectTypeDetector.POM_XML));
        boolean hasPackageJson = Files.isRegularFile(projectRoot.resolve(ProjectTypeDetector.PACKAGE_JSON));
        if (type == ProjectTypeEnum.MAVEN || (type == ProjectTypeEnum.MIXED && hasPom)) {
            constraints.addAll(extractJavaConstraints(projectRoot));
        }
        if (type == ProjectTypeEnum.NPM || (type == ProjectTypeEnum.MIXED && hasPackageJson)) {
            constraints.add(extractNodeConstraint(projectRoot));
        }
        if (type == ProjectTypeEnum.GO
                || (type == ProjectTypeEnum.MIXED
                && Files.isRegularFile(projectRoot.resolve(ProjectTypeDetector.GO_MOD)))) {
            constraints.add(extractGoConstraint(projectRoot));
        }
        if (type == ProjectTypeEnum.PYTHON
                || (type == ProjectTypeEnum.MIXED && ProjectTypeDetector.hasPythonManifest(projectRoot))) {
            constraints.add(extractPythonConstraint(projectRoot));
        }
        return constraints;
    }

    /**
     * 提取 JAVA 约束：BFS 遍历根 pom.xml 与其 {@code <modules>} 聚合树（深度 ≤
     * {@value #MAX_MODULE_DEPTH}，visited 防环）。合并规则：仅收集已声明版本（未声明的聚合根不算约束），
     * 按标准化版本去重（根优先）；全部未声明时回退单条 UNKNOWN（与单模块行为一致）。
     */
    private List<ProjectConstraint> extractJavaConstraints(Path projectRoot) {
        List<ProjectConstraint> result = new ArrayList<>();
        Map<String, ProjectConstraint> byVersion = new LinkedHashMap<>();
        Deque<Path> queue = new ArrayDeque<>();
        Set<Path> visited = new LinkedHashSet<>();
        queue.add(projectRoot.resolve(ProjectTypeDetector.POM_XML));
        int depth = 0;
        double rootConfidence = 0.0;
        boolean rootSeen = false;
        while (!queue.isEmpty() && depth <= MAX_MODULE_DEPTH) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                Path pomFile = queue.poll();
                if (!visited.add(pomFile.toAbsolutePath().normalize())) {
                    continue;
                }
                PomParser.ParsedPom parsed = pomParser.parse(pomFile);
                if (!rootSeen) {
                    rootSeen = true;
                    rootConfidence = parsed.confidence();
                }
                String version = parsed.javaVersion();
                if (version != null && !byVersion.containsKey(version)) {
                    ProjectConstraint constraint = new ProjectConstraint();
                    constraint.setLanguage(LanguageEnum.JAVA);
                    constraint.setConstraint(version);
                    constraint.setSourceFile(relativePomSource(projectRoot, pomFile));
                    constraint.setConfidence(parsed.confidence());
                    byVersion.put(version, constraint);
                    result.add(constraint);
                }
                for (Path child : pomParser.resolveModulePoms(pomFile)) {
                    queue.add(child);
                }
            }
            depth++;
        }
        if (result.isEmpty()) {
            ProjectConstraint unknown = new ProjectConstraint();
            unknown.setLanguage(LanguageEnum.JAVA);
            unknown.setConstraint(UNKNOWN);
            unknown.setSourceFile(SOURCE_POM);
            unknown.setConfidence(rootConfidence);
            result.add(unknown);
        }
        return result;
    }

    /** pom 路径相对项目根的展示形式（根 → {@value #SOURCE_POM}；子模块 → {@code module/pom.xml}）。 */
    private static String relativePomSource(Path projectRoot, Path pomFile) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path file = pomFile.toAbsolutePath().normalize();
        if (file.equals(root.resolve(ProjectTypeDetector.POM_XML))) {
            return SOURCE_POM;
        }
        if (file.startsWith(root)) {
            return root.relativize(file).toString().replace('\\', '/');
        }
        return file.toString();
    }

    /**
     * 提取 NODE 约束：.nvmrc（优先）→ .node-version → package.json 的 engines.node。
     * engines.node 保留原样声明的范围表达式（如 {@code >=20.0.0}），由 SdkVersionMatcher 承担语义匹配；
     * 均不存在 → UNKNOWN。置信度一律 1.0。
     */
    private ProjectConstraint extractNodeConstraint(Path projectRoot) {
        Path nvmrc = projectRoot.resolve(ProjectTypeDetector.NVMRC);
        Path nodeVersion = projectRoot.resolve(ProjectTypeDetector.NODE_VERSION);
        if (Files.isRegularFile(nvmrc)) {
            String version = cleanNodeVersion(readFirstLine(nvmrc));
            if (version != null) {
                return nodeConstraint(version, ProjectTypeDetector.NVMRC);
            }
        }
        if (Files.isRegularFile(nodeVersion)) {
            String version = cleanNodeVersion(readFirstLine(nodeVersion));
            if (version != null) {
                return nodeConstraint(version, ProjectTypeDetector.NODE_VERSION);
            }
        }
        String enginesNode = readEnginesNode(projectRoot);
        if (enginesNode != null) {
            return nodeConstraint(enginesNode, ProjectTypeDetector.PACKAGE_JSON);
        }
        return nodeConstraint(UNKNOWN, ProjectTypeDetector.NVMRC);
    }

    /**
     * 解析 package.json 的 {@code engines.node} 字符串声明。
     * 值 trim 后返回（空串视为未声明）；package.json 缺失 / 非对象 / 解析失败 / 值非字符串一律返回 null
     * （宽松语义：导入不因项目自带的 JSON 问题而失败）。
     */
    private static String readEnginesNode(Path projectRoot) {
        Path packageJson = projectRoot.resolve(ProjectTypeDetector.PACKAGE_JSON);
        if (!Files.isRegularFile(packageJson)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(packageJson)) {
            JsonNode root = JSON_MAPPER.readTree(in);
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode engines = root.get("engines");
            if (engines == null || !engines.isObject()) {
                return null;
            }
            JsonNode node = engines.get("node");
            if (node == null || !node.isTextual()) {
                return null;
            }
            String value = node.asText().trim();
            return value.isEmpty() ? null : value;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "package.json 解析失败，engines.node 视为未声明: {0}", packageJson);
            return null;
        }
    }

    /**
     * 读取文件首行（trim + 剥离 UTF-8 BOM）。.nvmrc / .node-version / .python-version 属无规范文本文件，
     * Windows 编辑器常写入 BOM；不剥离会使约束带不可见零宽字符导致静默失配。
     * 读失败按 IO 异常处理（与 PomParser 读盘语义一致）。
     */
    private static String readFirstLine(Path file) {
        try {
            String content = Files.readString(file);
            if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
                content = content.substring(1);
            }
            int newline = content.indexOf('\n');
            return (newline >= 0 ? content.substring(0, newline) : content).trim();
        } catch (IOException e) {
            throw new UncheckedIOException("读取 Node 版本声明失败: " + file, e);
        }
    }

    /**
     * 版本清洗：首行去前导 {@code v}（仅当后随数字，避免破坏 "latest" 类非版本值）。
     */
    private static String cleanNodeVersion(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        return (line.startsWith("v") && line.length() > 1 && Character.isDigit(line.charAt(1)))
                ? line.substring(1)
                : line;
    }

    /**
     * 构造 NODE 约束（置信度 1.0，直接声明；sourcePath 留空）。
     */
    private static ProjectConstraint nodeConstraint(String constraint, String sourceFile) {
        ProjectConstraint result = new ProjectConstraint();
        result.setLanguage(LanguageEnum.NODE);
        result.setConstraint(constraint);
        result.setSourceFile(sourceFile);
        result.setConfidence(1.0);
        return result;
    }

    /**
     * 提取 GO 约束：go.mod 的 {@code go} 指令（行首 `go 1.21` / `go 1.21.5`，
     * 容忍缩进与行尾注释）。go 指令语义为最低版本，约束值归一为 {@code >=<version>}（如 {@code >=1.21}）
     * 供 SdkVersionMatcher 按主版本语义匹配；指令缺失 / 文件读取失败 → UNKNOWN。置信度 1.0。
     */
    private static ProjectConstraint extractGoConstraint(Path projectRoot) {
        Path goMod = projectRoot.resolve(ProjectTypeDetector.GO_MOD);
        try {
            String content = Files.readString(goMod);
            for (String line : content.split("\\R")) {
                java.util.regex.Matcher matcher = GO_DIRECTIVE.matcher(line);
                if (matcher.find()) {
                    return languageConstraint(LanguageEnum.GO, ">=" + matcher.group(1),
                            ProjectTypeDetector.GO_MOD);
                }
            }
            return languageConstraint(LanguageEnum.GO, UNKNOWN, ProjectTypeDetector.GO_MOD);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "go.mod 读取失败，go 指令视为未声明: {0}", goMod);
            return languageConstraint(LanguageEnum.GO, UNKNOWN, ProjectTypeDetector.GO_MOD);
        }
    }

    /**
     * 提取 PYTHON 约束：.python-version 首行（优先，原样保留如 3.11.9 / 3.11）→
     * pyproject.toml {@code [project]} 段 requires-python 双引号值（原样保留范围表达式如 {@code >=3.9}）。
     * 均无有效声明 → UNKNOWN（sourceFile 取 .python-version）。置信度 1.0。
     */
    private static ProjectConstraint extractPythonConstraint(Path projectRoot) {
        Path versionFile = projectRoot.resolve(ProjectTypeDetector.PYTHON_VERSION);
        if (Files.isRegularFile(versionFile)) {
            String version = readFirstLine(versionFile);
            if (version != null && !version.isEmpty()) {
                return languageConstraint(LanguageEnum.PYTHON, version,
                        ProjectTypeDetector.PYTHON_VERSION);
            }
        }
        Path pyproject = projectRoot.resolve(ProjectTypeDetector.PYPROJECT_TOML);
        if (Files.isRegularFile(pyproject)) {
            String requires = readRequiresPython(pyproject);
            if (requires != null) {
                return languageConstraint(LanguageEnum.PYTHON, requires,
                        ProjectTypeDetector.PYPROJECT_TOML);
            }
        }
        return languageConstraint(LanguageEnum.PYTHON, UNKNOWN, ProjectTypeDetector.PYTHON_VERSION);
    }

    /**
     * 解析 pyproject.toml {@code [project]} 段的 requires-python 双引号字符串。
     * 仅在该段内行匹配（段头到下一个 {@code [} 表头间）；非双引号/缺失/读取失败 → null（宽松：不阻断识别）。
     */
    private static String readRequiresPython(Path pyproject) {
        try {
            String content = Files.readString(pyproject);
            boolean inProjectSection = false;
            for (String line : content.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[")) {
                    inProjectSection = trimmed.equals("[project]");
                    continue;
                }
                if (!inProjectSection) {
                    continue;
                }
                java.util.regex.Matcher matcher = REQUIRES_PYTHON.matcher(line);
                if (matcher.find()) {
                    String value = matcher.group(1).trim();
                    return value.isEmpty() ? null : value;
                }
            }
            return null;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "pyproject.toml 读取失败，requires-python 视为未声明: {0}",
                    pyproject);
            return null;
        }
    }

    /** 构造单语言约束（置信度 1.0；GO/PYTHON 复用）。 */
    private static ProjectConstraint languageConstraint(LanguageEnum language,
                                                        String constraint, String sourceFile) {
        ProjectConstraint result = new ProjectConstraint();
        result.setLanguage(language);
        result.setConstraint(constraint);
        result.setSourceFile(sourceFile);
        result.setConfidence(1.0);
        return result;
    }
}
