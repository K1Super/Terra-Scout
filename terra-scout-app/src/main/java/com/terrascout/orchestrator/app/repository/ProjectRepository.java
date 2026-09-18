package com.terrascout.orchestrator.app.repository;

import java.util.Optional;

import com.terrascout.orchestrator.core.domain.Project;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 项目仓储（表 project）。
 */
public interface ProjectRepository extends JpaRepository<Project, String> {

    /** 按项目根路径查找（uk_project_path 唯一；重复导入按幂等处理）。 */
    Optional<Project> findByProjectRootPath(String projectRootPath);
}
