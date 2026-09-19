package com.terrascout.orchestrator.app.repository;

import java.util.List;
import java.util.Optional;

import com.terrascout.orchestrator.core.domain.SdkVersion;
import com.terrascout.orchestrator.core.enums.ArchEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;
import com.terrascout.orchestrator.core.enums.OsTypeEnum;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * SDK 版本仓储（表 sdk_version；(language, version, os, arch) 唯一，与元数据字段一一对应）。
 */
public interface SdkVersionRepository extends JpaRepository<SdkVersion, String> {

    /** 按语言、OS、架构过滤（未指定维度时可传 null）。 */
    List<SdkVersion> findByLanguageAndOsAndArch(LanguageEnum language, OsTypeEnum os, ArchEnum arch);

    /** 按唯一键 (language, version, os, arch) 精确查找（元数据 upsert 主键定位）。 */
    Optional<SdkVersion> findByLanguageAndVersionAndOsAndArch(
            LanguageEnum language, String version, OsTypeEnum os, ArchEnum arch);
}
