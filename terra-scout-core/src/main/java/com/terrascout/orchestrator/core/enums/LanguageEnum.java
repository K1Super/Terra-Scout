package com.terrascout.orchestrator.core.enums;

/**
 * SDK 语言类型（ddl-migration.md 2.4 sdk_version.language；R29/R30 后 JAVA/NODE/PYTHON/GO 均支持系统探测）。
 */
public enum LanguageEnum {
    /** Java（JDK，Maven 生态）。 */
    JAVA,
    /** Node.js（npm 生态）。 */
    NODE,
    /** Python（R30 探测面：py -0p 全列 / python --version 回退）。 */
    PYTHON,
    /** Go（R30 探测面：GOROOT VERSION 文件 / go version 回退）。 */
    GO
}
