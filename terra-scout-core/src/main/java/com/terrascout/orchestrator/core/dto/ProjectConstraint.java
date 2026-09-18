package com.terrascout.orchestrator.core.dto;

import com.terrascout.orchestrator.core.enums.LanguageEnum;

/**
 * 项目版本约束（openapi ProjectConstraint；parser 模块 ConstraintExtractor 产出）。
 *
 * <p>constraint 为 Maven 风格版本范围或 semver range（如 {@code 17}、{@code [1.8,)}、{@code ^18.0.0}）。
 */
public class ProjectConstraint {

    /** 约束语言。 */
    private LanguageEnum language;

    /** Maven 风格版本约束或 semver range。 */
    private String constraint;

    /** 约束来源文件（如 pom.xml / package.json）。 */
    private String sourceFile;

    /** 约束来源文件路径（父 POM 场景非空）。 */
    private String sourcePath;

    /** 置信度 0-1（默认 1.0；properties 间接推导 < 1.0）。 */
    private Double confidence;

    public LanguageEnum getLanguage() {
        return language;
    }

    public void setLanguage(LanguageEnum language) {
        this.language = language;
    }

    public String getConstraint() {
        return constraint;
    }

    public void setConstraint(String constraint) {
        this.constraint = constraint;
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(String sourceFile) {
        this.sourceFile = sourceFile;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }
}
