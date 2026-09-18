package com.terrascout.orchestrator.app.repository;

import java.util.List;

import com.terrascout.orchestrator.core.domain.SdkInstallRecord;
import com.terrascout.orchestrator.core.enums.InstallStatusEnum;
import com.terrascout.orchestrator.core.enums.LanguageEnum;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * SDK 安装记录仓储（表 sdk_install_record）。
 */
public interface SdkInstallRecordRepository extends JpaRepository<SdkInstallRecord, String> {

    /** 按语言+版本+状态取记录（幂等安装去重用）。 */
    List<SdkInstallRecord> findByLanguageAndVersionAndStatus(
            LanguageEnum language, String version, InstallStatusEnum status);
}
