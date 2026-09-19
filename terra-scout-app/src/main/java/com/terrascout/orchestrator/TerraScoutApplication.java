package com.terrascout.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.terrascout.orchestrator.app.startup.StartupGuard;
import com.terrascout.orchestrator.core.constant.PathConstants;

/**
 * Terra Scout 内核启动入口（spring-boot-starter-web / data-jpa / flyway / h2）。
 *
 * <p>启动时序：
 * <ol>
 *   <li>归一化数据根覆盖：命令行 {@code --terrascout.data-dir=…} 同步进系统属性，保证
 *       {@link PathConstants#dataRoot()}（唯一路径口径）与 Spring 配置解析到同一目录；</li>
 *   <li>先执行数据库备份（尚无连接，文件一致性安全）；</li>
 *   <li>保证 {@code {data-root}} 目录树与 {@code db.properties} 就绪；</li>
 *   <li>再启动 Spring 上下文，由 Flyway 校验迁移（失败即 500006 语义）。</li>
 * </ol>
 */
@SpringBootApplication
public class TerraScoutApplication {

    /**
     * 启动入口：先归一化数据根、做数据库备份与目录/密钥引导，再启动 Spring。
     *
     * @param args 命令行参数（可含 {@code --terrascout.data-dir=…} / Token 等）
     */
    public static void main(String[] args) {
        applyDataDirOverride(args);
        StartupGuard.prepareDataRoot();
        StartupGuard.ensureDatabasePassword();
        SpringApplication.run(TerraScoutApplication.class, args);
    }

    /**
     * 把命令行数据根覆盖（{@code --terrascout.data-dir=…} 或
     * {@code --terrascout.data-dir …}）归一化进系统属性。
     *
     * <p>原由：Electron 以 {@code --key=value} 形式传递该覆盖，Spring 将其只注入
     * Environment（commandLineArgs 源），而 {@link PathConstants#dataRoot()} 只读系统
     * 属性——两者会解析出不同数据根（数据库在覆盖根、元数据/种子/密钥在默认根）。
     * 此处取命令行值写入系统属性，使两个口径收敛为同一目录；未提供时保持既有行为。
     *
     * @param args 原始命令行参数（与 Spring 的 SimpleCommandLineArgsParser 语义对齐）
     */
    static void applyDataDirOverride(String[] args) {
        String value = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--") || arg.length() == 2) {
                continue;
            }
            String option = arg.substring(2);
            int equals = option.indexOf('=');
            String key = equals >= 0 ? option.substring(0, equals) : option;
            if (!PathConstants.DATA_DIR_PROPERTY.equals(key)) {
                continue;
            }
            if (equals >= 0) {
                value = option.substring(equals + 1);
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                value = args[i + 1];
            }
            break;
        }
        if (value != null && !value.isBlank()) {
            System.setProperty(PathConstants.DATA_DIR_PROPERTY, value);
        }
    }
}
