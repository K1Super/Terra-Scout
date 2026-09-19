package com.terrascout.orchestrator.core.enums;

/**
 * SDK 安装记录状态（对应 sdk_install_record.status）。
 */
public enum InstallStatusEnum {
    /** 安装中。 */
    INSTALLING,
    /** 安装成功。 */
    SUCCESS,
    /** 安装失败。 */
    FAILED,
    /** 已回滚。 */
    ROLLED_BACK
}
