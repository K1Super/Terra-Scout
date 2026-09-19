package com.terrascout.orchestrator.app.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import com.terrascout.orchestrator.app.service.ProjectService;
import com.terrascout.orchestrator.core.domain.Project;
import com.terrascout.orchestrator.core.dto.AnalyzeRequest;
import com.terrascout.orchestrator.core.dto.AnalyzeResponse;
import com.terrascout.orchestrator.core.dto.ApiResponse;

import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目端点：导入分析 / 列表 / 详情 / 删除。
 *
 * <p>条目组装（含 type / constraints / plan）统一委托
 * {@link ProjectService#item(Project, boolean)} 与 {@link ProjectService#detailItem(String)}。
 */
@RestController
@RequestMapping("/api/v1/project")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /** 导入分析。 */
    @PostMapping("/analyze")
    public ApiResponse<AnalyzeResponse> analyze(@RequestBody AnalyzeRequest request) {
        return ApiResponse.ok(projectService.analyze(request));
    }

    /** 项目列表。 */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Project> paged = projectService.list(page, size);
        Map<String, Object> data = new HashMap<>();
        data.put("items", paged.getContent().stream()
                .map(project -> projectService.item(project, false))
                .collect(Collectors.toList()));
        data.put("total", paged.getTotalElements());
        data.put("page", page);
        data.put("size", size);
        return ApiResponse.ok(data);
    }

    /** 项目详情：type / constraints / plan 完整画像。 */
    @GetMapping("/{projectId}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String projectId) {
        return ApiResponse.ok(projectService.detailItem(projectId));
    }

    /** 删除项目（仅删记录不触碰磁盘）。 */
    @DeleteMapping("/{projectId}")
    public ApiResponse<Void> delete(@PathVariable String projectId) {
        projectService.delete(projectId);
        return ApiResponse.ok(null);
    }
}
