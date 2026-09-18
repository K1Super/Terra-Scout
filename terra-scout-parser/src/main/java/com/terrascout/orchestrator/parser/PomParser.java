package com.terrascout.orchestrator.parser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;

/**
 * pom.xml 解析器（pom-parser-algorithm.md 3.1~3.8）。
 *
 * <p>能力边界（P0）：
 * <ul>
 *   <li>版本约束提取按 7 级优先级（算法 3.2），值经标准化（算法 3.7：{@code 1.8→8}、{@code [17,18)→17}）</li>
 *   <li>属性占位符求值委托 {@link PropertyResolver}（算法 3.3）</li>
 *   <li>父 POM 递归合并：.m2 本地仓库 → ../pom.xml → 422003；最多 {@value #MAX_PARENT_DEPTH} 层，GAV 循环检测（算法 3.4）</li>
 *   <li>Profile 激活仅支持 activeByDefault / activation.jdk / activation.os.family（算法 3.5）</li>
 *   <li>多模块项目为 P0 冻结行为：直接抛 422001（算法 3.6）</li>
 * </ul>
 *
 * <p>线程安全：无共享可变状态，实例可并发使用（localRepository 只读）。
 */
public final class PomParser {

    /** 父 POM 递归深度上限（算法 3.4 步骤 e）。 */
    public static final int MAX_PARENT_DEPTH = 5;

    /** SpringBoot parent 的 groupId（用于优先级 5 版本推断）。 */
    private static final String SPRING_BOOT_GROUP_ID = "org.springframework.boot";

    /** SpringBoot parent 的 artifactId 集合（用于优先级 5 版本推断）。 */
    private static final Set<String> SPRING_BOOT_ARTIFACT_IDS =
            Set.of("spring-boot-starter-parent", "spring-boot-parent");

    /** 历史版本号（Maven 约定 1.5~1.9 等价于 5~9）。 */
    private static final Pattern LEGACY_VERSION = Pattern.compile("1\\.([5-9])");

    /** 占位符完整形态（优先级 6 编译器插件 release 值的间接引用判断）。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private static final String COMPILER_PLUGIN = "maven-compiler-plugin";

    private static final System.Logger LOG = System.getLogger(PomParser.class.getName());

    /** Maven 本地仓库（默认 %USERPROFILE%/.m2/repository，测试可注入）。 */
    private final Path localRepository;

    /** 使用默认本地仓库（算法 3.4 步骤 b 路径约定）。 */
    public PomParser() {
        this(Paths.get(System.getProperty("user.home"), ".m2", "repository"));
    }

    /**
     * 注入本地仓库路径（用于测试隔离）。
     *
     * @param localRepository Maven 本地仓库根目录
     */
    public PomParser(Path localRepository) {
        this.localRepository = Objects.requireNonNull(localRepository, "localRepository 不能为 null");
    }

    /**
     * 解析 pom.xml，产出 JAVA 版本约束（已按算法 3.7 标准化）。
     *
     * <p>多模块聚合（{@code <modules>}）不再冻结拒绝：本方法解析单个 POM 本身，
     * 模块遍历与约束合并由 {@link ConstraintExtractor} 驱动（裁决 R46）。
     *
     * @param pomFile pom.xml 文件路径
     * @return 解析结果（javaVersion 为 null 表示未声明任何版本，不报错）
     * @throws TerraScoutException 422003 父 POM 缺失/递归超限/GAV 循环；
     *                              422004 XML 非法或属性无法求值
     */
    public ParsedPom parse(Path pomFile) {
        Objects.requireNonNull(pomFile, "pomFile 不能为 null");
        if (!Files.isRegularFile(pomFile)) {
            throw new IllegalArgumentException("pom 文件不存在: " + pomFile);
        }
        Document dom = parseXml(pomFile);
        Element project = dom.getDocumentElement();

        List<Layer> layers = collectLayers(pomFile, dom);

        // 已声明属性键（pom 各层真实声明的属性，含激活 profile 覆盖后），不含运行时注入的系统回退值。
        Set<String> declaredProps = new LinkedHashSet<>();
        Map<String, String> mergedRaw = new LinkedHashMap<>();
        for (int i = layers.size() - 1; i >= 0; i--) {
            mergedRaw.putAll(layers.get(i).properties);
            declaredProps.addAll(layers.get(i).properties.keySet());
        }
        Layer self = layers.get(0);
        mergedRaw.put("project.version", nonNull(self.version));
        mergedRaw.put("project.groupId", nonNull(self.groupId));
        mergedRaw.put("project.artifactId", nonNull(self.artifactId));
        mergedRaw.put("project.basedir", pomFile.toAbsolutePath().getParent().toString());
        // 系统 java.version 仅作为占位符求值回退（如 ${java.version}），不得视为已声明的 java.version（会污染优先级 3）。
        mergedRaw.putIfAbsent("java.version", System.getProperty("java.version", ""));

        Map<String, String> resolved = PropertyResolver.resolve(mergedRaw);
        VersionDeclaration declaration = extractVersion(dom, resolved, mergedRaw, self, declaredProps);

        String javaVersion = declaration.version() == null ? null : normalizeVersion(declaration.version());
        LOG.log(System.Logger.Level.DEBUG, "pom 解析完成 artifactId={0} javaVersion={1} source={2}",
                self.artifactId, javaVersion, declaration.source());
        return new ParsedPom(self.groupId, self.artifactId, self.version,
                resolved, javaVersion, declaration.source(), declaration.confidence());
    }

    // ---------------------------------------------------------------- 层收集与父 POM 递归

    /**
     * 收集自身与父链各层（算法 3.4），返回顺序：子在前、父在后。
     */
    private List<Layer> collectLayers(Path pomFile, Document selfDom) {
        List<Layer> layers = new ArrayList<>();
        Set<String> seenGav = new LinkedHashSet<>();
        Set<Path> seenFiles = new LinkedHashSet<>();
        Path currentPom = pomFile.toAbsolutePath();
        Document currentDom = selfDom;
        while (true) {
            if (!seenFiles.add(currentPom)) {
                throw new TerraScoutException(TerraScoutError.PARENT_POM_MISSING,
                        "父 POM 文件循环引用: " + currentPom);
            }
            Layer layer = readLayer(currentDom);
            layers.add(layer);
            String gav = nonNull(layer.groupId) + ":" + nonNull(layer.artifactId) + ":" + nonNull(layer.version);
            if (!seenGav.add(gav) && layers.size() > 1) {
                throw new TerraScoutException(TerraScoutError.PARENT_POM_MISSING, "父 POM GAV 循环引用: " + gav);
            }
            if (layer.parentArtifactId == null) {
                break;
            }
            if (layers.size() > MAX_PARENT_DEPTH) {
                throw new TerraScoutException(TerraScoutError.PARENT_POM_MISSING,
                        "父 POM 递归超过 " + MAX_PARENT_DEPTH + " 层");
            }
            currentPom = locateParentPom(layer, currentPom);
            currentDom = parseXml(currentPom);
        }
        inheritCoordinates(layers);
        return layers;
    }

    /**
     * 定位父 POM：.m2 本地仓库优先，其次 ../pom.xml（算法 3.4 步骤 b/c），均未命中抛 422003。
     */
    private Path locateParentPom(Layer layer, Path currentPom) {
        String groupPath = layer.parentGroupId.replace('.', '/');
        Path repoPom = localRepository.resolve(Paths.get(groupPath,
                layer.parentArtifactId, layer.parentVersion, layer.parentArtifactId + "-" + layer.parentVersion + ".pom"));
        if (Files.isRegularFile(repoPom)) {
            LOG.log(System.Logger.Level.DEBUG, "父 POM 命中本地仓库 {0}", repoPom);
            return repoPom;
        }
        Path sibling = currentPom.toAbsolutePath().getParent().getParent()
                .resolve(ProjectTypeDetector.POM_XML);
        if (Files.isRegularFile(sibling)) {
            LOG.log(System.Logger.Level.DEBUG, "父 POM 命中同级目录 {0}", sibling);
            return sibling;
        }
        throw new TerraScoutException(TerraScoutError.PARENT_POM_MISSING,
                "父 POM 未找到: " + layer.parentGroupId + ":" + layer.parentArtifactId + ":" + layer.parentVersion);
    }

    /**
     * Maven 继承规则：子省略的 groupId / version 继承自父（artifactId 必须显式）。
     */
    private static void inheritCoordinates(List<Layer> layers) {
        for (int i = layers.size() - 2; i >= 0; i--) {
            Layer child = layers.get(i);
            Layer parent = layers.get(i + 1);
            if (child.groupId == null) {
                child.groupId = parent.groupId;
            }
            if (child.version == null) {
                child.version = parent.version;
            }
        }
    }

    // ---------------------------------------------------------------- 版本提取（算法 3.2 七级优先级）

    /**
     * 七级优先级提取原始版本声明。优先级 1~4 仅考虑 pom 已声明（declaredProps 中）的属性，
     * 运行时注入的系统 java.version 回退不参与（否则会遮蔽优先级 5/6 与未声明语义）。
     */
    private VersionDeclaration extractVersion(Document dom, Map<String, String> resolved,
                                               Map<String, String> raw, Layer self, Set<String> declaredProps) {
        if (declaredProps.contains("maven.compiler.release")) {
            String value = resolved.get("maven.compiler.release");
            if (value != null) {
                return new VersionDeclaration(value, VersionSource.COMPILER_RELEASE,
                        declaredConfidence(raw, "maven.compiler.release"));
            }
        }
        if (declaredProps.contains("maven.compiler.target")) {
            String value = resolved.get("maven.compiler.target");
            if (value != null) {
                return new VersionDeclaration(value, VersionSource.COMPILER_TARGET,
                        declaredConfidence(raw, "maven.compiler.target"));
            }
        }
        if (declaredProps.contains("java.version")) {
            String value = resolved.get("java.version");
            if (value != null) {
                return new VersionDeclaration(value, VersionSource.JAVA_VERSION,
                        declaredConfidence(raw, "java.version"));
            }
        }
        if (declaredProps.contains("jdk.version")) {
            String value = resolved.get("jdk.version");
            if (value != null) {
                return new VersionDeclaration(value, VersionSource.JDK_VERSION,
                        declaredConfidence(raw, "jdk.version"));
            }
        }
        VersionDeclaration parentInference = inferFromParent(self);
        if (parentInference != null) {
            return parentInference;
        }
        String pluginRelease = extractCompilerPluginRelease(dom, resolved);
        if (pluginRelease != null) {
            return new VersionDeclaration(pluginRelease, VersionSource.PLUGIN_RELEASE, 1.0);
        }
        return new VersionDeclaration(null, VersionSource.DECLARED_NOWHERE, 1.0);
    }

    /**
     * 优先级 5：SpringBoot parent 版本推断（3.x → 17，1.x/2.x → 8），置信度 0.6。
     */
    private static VersionDeclaration inferFromParent(Layer self) {
        if (self.parentArtifactId == null
                || !SPRING_BOOT_GROUP_ID.equals(self.parentGroupId)
                || !SPRING_BOOT_ARTIFACT_IDS.contains(self.parentArtifactId)) {
            return null;
        }
        Matcher major = Pattern.compile("^(\\d+)").matcher(self.parentVersion);
        if (!major.find()) {
            return null;
        }
        String java = Integer.parseInt(major.group(1)) >= 3 ? "17" : "8";
        return new VersionDeclaration(java, VersionSource.PARENT_INFERENCE, 0.6);
    }

    /**
     * 优先级 6：build/plugins 中 maven-compiler-plugin 的 configuration/release（支持完整占位符间接引用）。
     */
    private String extractCompilerPluginRelease(Document dom, Map<String, String> resolved) {
        for (Element build : childrenNamed(dom.getDocumentElement(), "build")) {
            for (Element plugins : childrenNamed(build, "plugins")) {
                for (Element plugin : childrenNamed(plugins, "plugin")) {
                    if (!COMPILER_PLUGIN.equals(childText(plugin, "artifactId"))) {
                        continue;
                    }
                    for (Element config : childrenNamed(plugin, "configuration")) {
                        for (Element release : childrenNamed(config, "release")) {
                            return resolveFullPlaceholder(release.getTextContent().trim(), resolved);
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 完整占位符（如 {@code ${maven.compiler.release}}）在属性表存在则替换，否则原样返回。
     */
    private static String resolveFullPlaceholder(String value, Map<String, String> resolved) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        if (matcher.matches()) {
            String indirect = resolved.get(matcher.group(1));
            return indirect != null ? indirect : value;
        }
        return value;
    }

    /**
     * 间接声明（原始值含占位符）置信度 0.9，否则 1.0（ProjectConstraint.confidence 约定）。
     */
    private static double declaredConfidence(Map<String, String> raw, String key) {
        String rawValue = raw.get(key);
        return rawValue != null && rawValue.contains("${") ? 0.9 : 1.0;
    }

    // ---------------------------------------------------------------- 版本标准化（算法 3.7）

    /**
     * 版本约束标准化：范围取左端点（{@code [17,18)→17}），历史版本 {@code 1.8→8}，其余原样。
     * 无左端点的范围（如 {@code ,17]}）保留原样交由版本匹配层处理。
     */
    static String normalizeVersion(String rawValue) {
        String value = rawValue.trim();
        if (value.startsWith("[") || value.startsWith("(")) {
            int comma = value.indexOf(',');
            String left = comma > 0 ? value.substring(1, comma).trim() : "";
            return left.isEmpty() ? value : normalizeLegacyJavaVersion(left);
        }
        return normalizeLegacyJavaVersion(value);
    }

    /**
     * 历史版本号标准化：Maven 约定 {@code 1.5~1.9} 等价于 {@code 5~9}。
     */
    static String normalizeLegacyJavaVersion(String value) {
        Matcher matcher = LEGACY_VERSION.matcher(value);
        return matcher.matches() ? matcher.group(1) : value;
    }

    // ---------------------------------------------------------------- 属性与 Profile（算法 3.3 / 3.5）

    /**
     * 提取单层属性：主 properties + 激活 profile 的 properties（profile 覆盖主，Maven 语义）。
     */
    private static Map<String, String> extractLayerProperties(Element project) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (Element element : childrenNamed(project, "properties")) {
            readPropertyEntries(element, properties);
        }
        for (Element profiles : childrenNamed(project, "profiles")) {
            for (Element profile : childrenNamed(profiles, "profile")) {
                if (isProfileActive(profile)) {
                    for (Element element : childrenNamed(profile, "properties")) {
                        readPropertyEntries(element, properties);
                    }
                }
            }
        }
        return properties;
    }

    /**
     * 读取 properties 元素下的叶子键值对。
     */
    private static void readPropertyEntries(Element properties, Map<String, String> target) {
        NodeList children = properties.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element entry) {
                String key = entry.getTagName();
                String value = entry.getTextContent().trim();
                if (!key.isEmpty() && value != null) {
                    target.put(key, value);
                }
            }
        }
    }

    /**
     * Profile 激活判定（算法 3.5）：activeByDefault=true / activation.jdk 匹配当前 JVM /
     * activation.os.family 匹配 WINDOWS。property 激活与命令行 -P 为 P1。
     */
    private static boolean isProfileActive(Element profile) {
        Element activation = firstChild(profile, "activation");
        if (activation == null) {
            return false;
        }
        String activeByDefault = childText(activation, "activeByDefault");
        if (Boolean.parseBoolean(activeByDefault)) {
            return true;
        }
        String jdk = childText(activation, "jdk");
        if (jdk != null && jdkRangeMatches(jdk)) {
            return true;
        }
        Element os = firstChild(activation, "os");
        if (os != null) {
            String family = childText(os, "family");
            if (family != null && osFamilyMatches(family)) {
                return true;
            }
        }
        return false;
    }

    /**
     * jdk 范围匹配当前 JVM 主版本：支持 {@code 17}（精确）、{@code [1.8,17)}、{@code [17,)}、{@code (,17]} 等形态。
     */
    private static boolean jdkRangeMatches(String range) {
        int current = Runtime.version().feature();
        String value = range.trim();
        if (!value.startsWith("[") && !value.startsWith("(")) {
            return current == majorOf(value);
        }
        boolean leftInclusive = value.startsWith("[");
        boolean rightInclusive = value.endsWith("]");
        String body = value.substring(1, value.endsWith("]") || value.endsWith(")") ? value.length() - 1 : value.length());
        int comma = body.indexOf(',');
        String left = comma >= 0 ? body.substring(0, comma).trim() : body.trim();
        String right = comma >= 0 ? body.substring(comma + 1).trim() : "";
        if (!left.isEmpty()) {
            int lower = majorOf(left);
            if (leftInclusive ? current < lower : current <= lower) {
                return false;
            }
        }
        if (!right.isEmpty()) {
            int upper = majorOf(right);
            if (rightInclusive ? current > upper : current >= upper) {
                return false;
            }
        }
        return true;
    }

    /**
     * 版本字符串取主版本号（{@code 17.0.9→17}、{@code 1.8→8}）。
     */
    private static int majorOf(String version) {
        Matcher legacy = LEGACY_VERSION.matcher(version.trim());
        if (legacy.matches()) {
            return Integer.parseInt(legacy.group(1));
        }
        Matcher matcher = Pattern.compile("^(\\d+)").matcher(version.trim());
        if (!matcher.find()) {
            throw new TerraScoutException(TerraScoutError.PROPERTY_UNRESOLVED, "无法识别的版本号: " + version);
        }
        return Integer.parseInt(matcher.group(1));
    }

    /**
     * os.family 匹配：P0 仅支持 WINDOWS（p0-scope 1.1 操作系统冻结）。
     */
    private static boolean osFamilyMatches(String family) {
        if (!"windows".equals(family.trim().toLowerCase(Locale.ROOT))) {
            return false;
        }
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    // ---------------------------------------------------------------- 多模块解析（裁决 R46）

    /**
     * 解析聚合 POM 的 {@code <modules>} 列表，返回各子模块 pom.xml 绝对路径（按声明顺序去重）。
     *
     * <p>模块条目以聚合 POM 所在目录为基准解析；条目指向的 pom.xml 不存在时告警并跳过
     * （导入分析容错，构建期由 Maven 揭示真实错误）。路径安全：只读不写；无 {@code <modules>}
     * 返回空表。
     *
     * @param aggregatorPom 聚合 POM 文件路径
     * @return 子模块 pom.xml 绝对路径列表（可能为空）
     */
    public List<Path> resolveModulePoms(Path aggregatorPom) {
        Objects.requireNonNull(aggregatorPom, "aggregatorPom 不能为 null");
        if (!Files.isRegularFile(aggregatorPom)) {
            throw new IllegalArgumentException("pom 文件不存在: " + aggregatorPom);
        }
        Document dom = parseXml(aggregatorPom);
        Path baseDir = aggregatorPom.toAbsolutePath().normalize().getParent();
        List<Path> poms = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (Element modules : childrenNamed(dom.getDocumentElement(), "modules")) {
            for (Element module : childrenNamed(modules, "module")) {
                String entry = module.getTextContent().trim();
                if (entry.isEmpty()) {
                    continue;
                }
                Path modulePom = baseDir.resolve(entry).normalize().resolve(ProjectTypeDetector.POM_XML);
                if (!Files.isRegularFile(modulePom)) {
                    LOG.log(System.Logger.Level.WARNING, "聚合模块缺失 pom.xml，导入分析跳过: {0}", modulePom);
                    continue;
                }
                if (seen.add(modulePom)) {
                    poms.add(modulePom);
                }
            }
        }
        return poms;
    }

    // ---------------------------------------------------------------- XML 安全解析

    /**
     * 解析 XML：禁 DOCTYPE 与外部实体（XXE 防护，刚性安全底线）；非法 XML 抛 422004。
     */
    private static Document parseXml(Path pomFile) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            // 自定义 ErrorHandler：替换 JAXP 默认（会把 fatal error 打到 stderr），
            // 仅在解析异常时抛出交由上层转 422004，避免污染日志（工程规范 3.6）。
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException exception) {
                    // 可恢复告警：忽略。
                }

                @Override
                public void error(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            return builder.parse(pomFile.toFile());
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML 解析器配置失败", e);
        } catch (SAXException e) {
            throw new TerraScoutException(TerraScoutError.PROPERTY_UNRESOLVED,
                    "XML 格式非法: " + pomFile + " (" + e.getMessage() + ")");
        } catch (IOException e) {
            throw new UncheckedIOException("读取 pom 失败: " + pomFile, e);
        }
    }

    // ---------------------------------------------------------------- DOM 工具

    private static List<Element> childrenNamed(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && name.equals(element.getTagName())) {
                result.add(element);
            }
        }
        return result;
    }

    private static Element firstChild(Element parent, String name) {
        for (Element element : childrenNamed(parent, name)) {
            return element;
        }
        return null;
    }

    private static String childText(Element parent, String name) {
        Element child = firstChild(parent, name);
        return child == null ? null : child.getTextContent().trim();
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    // ---------------------------------------------------------------- 内部模型

    /** 版本声明来源（算法 3.2 七级优先级）。 */
    public enum VersionSource {
        /** 优先级 1：properties/maven.compiler.release。 */
        COMPILER_RELEASE,
        /** 优先级 2：properties/maven.compiler.target。 */
        COMPILER_TARGET,
        /** 优先级 3：properties/java.version。 */
        JAVA_VERSION,
        /** 优先级 4：properties/jdk.version。 */
        JDK_VERSION,
        /** 优先级 5：SpringBoot parent 版本推断。 */
        PARENT_INFERENCE,
        /** 优先级 6：编译器插件 configuration/release。 */
        PLUGIN_RELEASE,
        /** 优先级 7：未声明任何版本。 */
        DECLARED_NOWHERE
    }

    /** 版本声明中间结果。 */
    private record VersionDeclaration(String version, VersionSource source, double confidence) {
    }

    /**
     * 解析结果：坐标、求值后的属性表、JAVA 版本约束（已标准化）与来源。
     *
     * <p>{@link #javaVersion()} 为 null 表示未声明任何版本（不报错，约束值由上层表达为 UNKNOWN）。
     */
    public record ParsedPom(String groupId, String artifactId, String version,
                            Map<String, String> properties,
                            String javaVersion,
                            VersionSource source,
                            double confidence) {

        public ParsedPom {
            properties = properties == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        }

        /** 约束值（未声明时为字面量 UNKNOWN，rest-schema 3.4.1 constraint 字段语义）。 */
        public String javaVersionOrUnknown() {
            return javaVersion == null ? "UNKNOWN" : javaVersion;
        }
    }

    /** 单层 POM 的可变中间模型（坐标在继承修正前允许为 null）。 */
    private static final class Layer {
        private String groupId;
        private String artifactId;
        private String version;
        private Map<String, String> properties = new LinkedHashMap<>();
        private String parentGroupId;
        private String parentArtifactId;
        private String parentVersion;
    }

    /** 读取单层 POM 的坐标、父引用与属性。 */
    private static Layer readLayer(Document dom) {
        Element project = dom.getDocumentElement();
        Layer layer = new Layer();
        layer.groupId = childText(project, "groupId");
        layer.artifactId = childText(project, "artifactId");
        layer.version = childText(project, "version");
        Element parent = firstChild(project, "parent");
        if (parent != null) {
            layer.parentGroupId = childText(parent, "groupId");
            layer.parentArtifactId = childText(parent, "artifactId");
            layer.parentVersion = childText(parent, "version");
            if (layer.parentGroupId == null || layer.parentArtifactId == null || layer.parentVersion == null) {
                throw new TerraScoutException(TerraScoutError.PARENT_POM_MISSING,
                        "parent 坐标不完整: groupId=" + layer.parentGroupId
                                + ", artifactId=" + layer.parentArtifactId + ", version=" + layer.parentVersion);
            }
        }
        layer.properties = extractLayerProperties(project);
        return layer;
    }
}
