package com.terrascout.orchestrator.app.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.terrascout.orchestrator.app.repository.ProjectRepository;
import com.terrascout.orchestrator.app.repository.SdkInstallRecordRepository;
import com.terrascout.orchestrator.app.repository.SdkVersionRepository;
import com.terrascout.orchestrator.app.step.SdkVersionMatcher;
import com.terrascout.orchestrator.core.domain.Project;
import com.terrascout.orchestrator.core.domain.SdkInstallRecord;
import com.terrascout.orchestrator.core.domain.SdkVersion;
import com.terrascout.orchestrator.core.dto.AnalyzeRequest;
import com.terrascout.orchestrator.core.dto.AnalyzeResponse;
import com.terrascout.orchestrator.core.dto.InstallPlan;
import com.terrascout.orchestrator.core.dto.ProjectConstraint;
import com.terrascout.orchestrator.core.dto.SdkInstallItem;
import com.terrascout.orchestrator.core.enums.ArchEnum;
import com.terrascout.orchestrator.core.enums.InstallStatusEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.OsTypeEnum;
import com.terrascout.orchestrator.core.enums.ProjectTypeEnum;
import com.terrascout.orchestrator.core.error.TerraScoutError;
import com.terrascout.orchestrator.core.error.TerraScoutException;
import com.terrascout.orchestrator.parser.ConstraintExtractor;
import com.terrascout.orchestrator.parser.ProjectTypeDetector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 项目服务：导入分析 / 列表 / 详情 / 删除。
 *
 * <p>路径安全（安全红线）：外部输入路径先规范化（{@link Path#normalize()}）
 * 并校验为已存在目录；项目根不存在 → 404001（项目路径不存在）。
 */
@Service
public class ProjectService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectService.class);

    /** 422001 归一化提示（扩展四语言）。 */
    private static final String HINT_UNRECOGNIZED =
            "请选择包含 pom.xml（Maven）、package.json（npm）、go.mod（Go）、"
                    + ".python-version 或 pyproject.toml（Python）的项目根目录；"
                    + "若项目位于某个子目录中，请直接选择该子目录";

    private final ProjectRepository repository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final ConstraintExtractor constraintExtractor;
    private final SdkVersionRepository sdkVersionRepository;
    private final SdkInstallRecordRepository installRecordRepository;

    public ProjectService(ProjectRepository repository,
                          AuditService auditService,
                          ObjectMapper objectMapper,
                          ConstraintExtractor constraintExtractor,
                          SdkVersionRepository sdkVersionRepository,
                          SdkInstallRecordRepository installRecordRepository) {
        this.repository = repository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.constraintExtractor = constraintExtractor;
        this.sdkVersionRepository = sdkVersionRepository;
        this.installRecordRepository = installRecordRepository;
    }

    /** 导入分析：解析并持久化项目画像。 */
    @Transactional
    public AnalyzeResponse analyze(AnalyzeRequest request) {
        String rawPath = request.getPath();
        if (rawPath == null || rawPath.isBlank()) {
            throw new TerraScoutException(TerraScoutError.INVALID_PROJECT_PATH);
        }
        Path root = normalizeAndValidate(rawPath);
        ProjectTypeEnum type = ProjectTypeDetector.detect(root);
        if (type == ProjectTypeEnum.UNKNOWN) {
            root = resolveEffectiveRoot(root);
            type = ProjectTypeDetector.detect(root);
        }

        List<ProjectConstraint> constraints = constraintExtractor.extract(root);
        String profileJson = toJson(profileOf(type, constraints));

        Project project = repository.findByProjectRootPath(root.toString())
                .orElseGet(Project::new);
        project.setId(project.getId() != null ? project.getId()
                : UUID.randomUUID().toString());
        project.setName(root.getFileName() != null ? root.getFileName().toString() : "project");
        project.setProjectRootPath(root.toString());
        project.setOsType(com.terrascout.orchestrator.core.enums.OsTypeEnum.WINDOWS);
        long now = System.currentTimeMillis();
        if (repository.existsById(project.getId()) == false) {
            project.setCreatedAt(now);
        }
        project.setUpdatedAt(now);
        project.setProfileJson(profileJson);
        repository.save(project);

        auditService.record(project.getId(), "PROJECT_ANALYZE", "project", project.getId(),
                null, profileJson, "SUCCESS");

        return buildResponse(project, type, constraints);
    }

    /** 项目列表（分页）。 */
    @Transactional(readOnly = true)
    public Page<Project> list(int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        return repository.findAll(PageRequest.of(safePage - 1, safeSize,
                Sort.by(Sort.Direction.DESC, "updatedAt")));
    }

    /** 项目详情，不存在 → 404 等效回收（PLAN_NOT_FOUND）。 */
    @Transactional(readOnly = true)
    public Project detail(String projectId) {
        return repository.findById(projectId)
                .orElseThrow(() -> new TerraScoutException(TerraScoutError.PLAN_NOT_FOUND));
    }

    /** 详情条目：含 type / constraints / plan 的完整画像。 */
    @Transactional(readOnly = true)
    public Map<String, Object> detailItem(String projectId) {
        return item(detail(projectId), true);
    }

    /** 列表/详情条目组装：基础字段 + type；includeProfile 时附加 constraints 与预览计划。 */
    public Map<String, Object> item(Project project, boolean includeProfile) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("projectId", project.getId());
        item.put("name", project.getName());
        item.put("projectRootPath", project.getProjectRootPath());
        item.put("osType", project.getOsType() == null ? OsTypeEnum.WINDOWS : project.getOsType());
        Profile profile = profileOf(project);
        item.put("type", profile.type);
        if (includeProfile) {
            item.put("constraints", profile.constraints);
            item.put("plan", previewPlan(project, profile.constraints));
        }
        item.put("createdAt", project.getCreatedAt());
        item.put("updatedAt", project.getUpdatedAt());
        return item;
    }

    /** 删除项目：仅删数据库记录，不触碰磁盘 .devenv。 */
    @Transactional
    public void delete(String projectId) {
        Project project = detail(projectId);
        repository.delete(project);
        auditService.record(projectId, "PROJECT_DELETE", "project", projectId,
                null, null, "SUCCESS");
    }

    /** 规范化 + 校验路径为已存在目录（安全红线约束）。 */
    private Path normalizeAndValidate(String rawPath) {
        Path normalized = Paths.get(rawPath).normalize().toAbsolutePath();
        if (!Files.isDirectory(normalized)) {
            throw new TerraScoutException(TerraScoutError.PROJECT_PATH_NOT_FOUND);
        }
        return normalized;
    }

    /**
     * 根目录无声明文件时解析有效项目根（扩展四语言声明文件）：直接子目录恰有一个含
     * pom.xml / package.json / go.mod / .python-version / pyproject.toml 则采纳为项目根；
     * 否则抛 422001 并携带 selectedPath / candidates / hint 明细供前端呈现。
     */
    private Path resolveEffectiveRoot(Path selectedRoot) {
        List<Path> candidates = ProjectTypeDetector.findNestedCandidates(selectedRoot);
        if (candidates.size() == 1) {
            Path adopted = candidates.get(0);
            LOGGER.warn("导入目录未直接包含声明文件，采纳唯一子目录作为项目根: {} -> {}", selectedRoot, adopted);
            return adopted;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("selectedPath", selectedRoot.toString());
        details.put("reason", "所选目录及其直接子目录中未找到唯一项目声明文件"
                + "（pom.xml / package.json / go.mod / .python-version / pyproject.toml）");
        details.put("candidates", candidates.stream().map(Path::toString).collect(Collectors.toList()));
        details.put("hint", HINT_UNRECOGNIZED);
        LOGGER.warn("导入目录无法识别项目类型: {}, 候选子目录: {}", selectedRoot, details.get("candidates"));
        throw new TerraScoutException(TerraScoutError.PROJECT_TYPE_UNKNOWN, details);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 profile_json 失败", e);
        }
    }

    /** profile_json 结构：type + constraints 完整画像（旧版仅约束数组，读取时兼容回退）。 */
    private Map<String, Object> profileOf(ProjectTypeEnum type, List<ProjectConstraint> constraints) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("type", type.name());
        profile.put("constraints", constraints);
        return profile;
    }

    /**
     * 从 profile_json 反序列化画像。新版 {@code {type, constraints}}；旧版纯约束数组
     * （升级前入库记录）则回退磁盘重检；解析失败同样回退（不阻断详情/列表）。
     */
    private Profile profileOf(Project project) {
        String json = project.getProfileJson();
        if (json != null && !json.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(json);
                if (root.isArray()) {
                    return detectFromDisk(project);
                }
                ProjectTypeEnum type = root.get("type") == null || root.get("type").isNull()
                        ? null : ProjectTypeEnum.valueOf(root.get("type").asText());
                List<ProjectConstraint> constraints = root.get("constraints") == null
                        || root.get("constraints").isNull()
                        ? List.of()
                        : objectMapper.convertValue(root.get("constraints"),
                                new TypeReference<List<ProjectConstraint>>() {
                                });
                return new Profile(type, constraints);
            } catch (Exception e) {
                LOGGER.warn("profile_json 反序列化失败，回退磁盘重检: project={}", project.getId(), e);
            }
        }
        return detectFromDisk(project);
    }

    /** 磁盘重检画像（旧数据兜底）：路径缺失/不可识别时依次按 404001 / 422001 语义抛出。 */
    private Profile detectFromDisk(Project project) {
        Path root = normalizeAndValidate(project.getProjectRootPath());
        ProjectTypeEnum type = ProjectTypeDetector.detect(root);
        if (type == ProjectTypeEnum.UNKNOWN) {
            root = resolveEffectiveRoot(root);
            type = ProjectTypeDetector.detect(root);
        }
        return new Profile(type, constraintExtractor.extract(root));
    }

    /**
     * 预览装配计划：按约束实时匹配推荐 SDK 版本（与 execute 阶段 MatchVersionStep 同源），
     * 供详情页展示与确认装配入口使用；单条约束匹配失败仅跳过并告警，不阻断详情（authoritative
     * 校验仍在 execute 的 MatchVersionStep）。
     */
    private InstallPlan previewPlan(Project project, List<ProjectConstraint> constraints) {
        InstallPlan plan = new InstallPlan();
        plan.setPlanId(project.getId());
        List<SdkInstallItem> items = new ArrayList<>();
        for (ProjectConstraint constraint : constraints) {
            try {
                LanguageEnum language = constraint.getLanguage();
                Set<String> installed = installRecordRepository.findAll().stream()
                        .filter(r -> r.getStatus() == InstallStatusEnum.SUCCESS && r.getLanguage() == language)
                        .map(SdkInstallRecord::getVersion)
                        .collect(Collectors.toSet());
                List<SdkVersion> available = sdkVersionRepository.findByLanguageAndOsAndArch(
                        language, OsTypeEnum.WINDOWS, ArchEnum.AMD64);
                items.add(SdkVersionMatcher.match(constraint.getConstraint(), installed, available));
            } catch (TerraScoutException e) {
                LOGGER.warn("预览装配计划跳过约束 {} {}: {}",
                        constraint.getLanguage(), constraint.getConstraint(), e.getMessage());
            }
        }
        plan.setSdkInstalls(items);
        plan.setDependencyInstalls(List.of());
        plan.setVerifyCommands(List.of());
        return plan;
    }

    /** 项目画像（类型 + 约束），从 profile_json 恢复。 */
    private static final class Profile {
        final ProjectTypeEnum type;
        final List<ProjectConstraint> constraints;

        Profile(ProjectTypeEnum type, List<ProjectConstraint> constraints) {
            this.type = type;
            this.constraints = constraints;
        }
    }

    private AnalyzeResponse buildResponse(Project project,
                                          ProjectTypeEnum type,
                                          List<ProjectConstraint> constraints) {
        AnalyzeResponse response = new AnalyzeResponse();
        response.setProjectId(project.getId());
        response.setName(project.getName());
        response.setType(type);
        response.setOsType(project.getOsType());
        response.setConstraints(constraints);
        response.setPlan(previewPlan(project, constraints));
        return response;
    }
}
