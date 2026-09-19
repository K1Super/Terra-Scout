package com.terrascout.orchestrator.core.enums;

/**
 * 项目类型（AnalyzeResponse.type / ProjectListItem.type；解析器 ProjectTypeDetector 输出）。
 */
public enum ProjectTypeEnum {
    /** 纯 Maven 项目（仅 pom.xml）。 */
    MAVEN,
    /** 纯 npm 项目（仅 package.json）。 */
    NPM,
    /** 纯 Go 项目（仅 go.mod）。 */
    GO,
    /** 纯 Python 项目（仅 .python-version / pyproject.toml）。 */
    PYTHON,
    /** 混合项目（2 种及以上语言声明文件并存，约束多读，冲突抛 422002）。 */
    MIXED,
    /** 无法识别（无任何声明文件，对应 422001）。 */
    UNKNOWN
}
