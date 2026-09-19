package com.terrascout.orchestrator.core.enums;

/**
 * SDK 语言类型（对应 sdk_version.language；JAVA/NODE/PYTHON/GO 均支持系统探测）。
 */
public enum LanguageEnum {
    /** Java（JDK，Maven 生态）。 */
    JAVA,
    /** Node.js（npm 生态）。 */
    NODE,
    /** Python（探测面：py -0p 全列 / python --version 回退）。 */
    PYTHON,
    /** Go（探测面：GOROOT VERSION 文件 / go version 回退）。 */
    GO
}
