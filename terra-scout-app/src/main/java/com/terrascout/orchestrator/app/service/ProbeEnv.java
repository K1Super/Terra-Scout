package com.terrascout.orchestrator.app.service;

/**
 * 环境访问注入缝：环境变量与系统属性统一入口，探测类不得直接触碰
 * {@code System.getenv / System.getProperty}；单测可注入 Map 实现。
 */
interface ProbeEnv {

    /** 读环境变量（不存在返回 null）。 */
    String get(String key);

    /** 读系统属性（不存在返回 null）。 */
    String getProperty(String key);

    /** 默认实现：真实进程环境。 */
    static ProbeEnv system() {
        return new ProbeEnv() {
            @Override
            public String get(String key) {
                return System.getenv(key);
            }

            @Override
            public String getProperty(String key) {
                return System.getProperty(key);
            }
        };
    }
}
