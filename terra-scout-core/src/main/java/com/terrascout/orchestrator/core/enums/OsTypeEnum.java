package com.terrascout.orchestrator.core.enums;

/**
 * 操作系统类型（对应 project.os_type / sdk_version.os；P0 仅 WINDOWS）。
 */
public enum OsTypeEnum {
    /** Windows 10/11（P0 唯一支持平台）。 */
    WINDOWS,
    /** macOS（P1 预留）。 */
    MACOS,
    /** Linux（P1 预留）。 */
    LINUX
}
