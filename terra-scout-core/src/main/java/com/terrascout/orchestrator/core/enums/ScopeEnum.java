package com.terrascout.orchestrator.core.enums;

/**
 * 安装记录归属（ddl-migration.md 2.4 sdk_install_record.scope；master-plan D-004）。
 *
 * <p>语义：记录由哪类操作触发，<b>不表达物理位置</b>——SDK 物理上始终在全局仓库
 * {@code {data-root}\sdks}；PROJECT 表示该记录由项目装配触发创建。
 */
public enum ScopeEnum {
    /** 全局操作触发（用户在 SDK 管理页直接安装）。 */
    GLOBAL,
    /** 项目装配触发（项目执行装配计划时创建）。 */
    PROJECT
}
