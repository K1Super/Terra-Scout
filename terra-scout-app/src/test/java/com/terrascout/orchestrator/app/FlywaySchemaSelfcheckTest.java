package com.terrascout.orchestrator.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway 基线自检（非容器）：裸跑 Flyway 验证 {@code V1__init.sql} 在原生 H2 上能完整执行，
 * 且 P0 全部核心表与唯一迁移账本（flyway_schema_history）均创建（ddl-migration.md 2.3 / D-005）。
 */
class FlywaySchemaSelfcheckTest {

    @Test
    void v1CreatesAllP0Tables() throws Exception {
        String url = "jdbc:h2:mem:flyway-selfcheck-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        org.h2.jdbcx.JdbcDataSource jds = new org.h2.jdbcx.JdbcDataSource();
        jds.setURL(url);
        jds.setUser("sa");
        jds.setPassword("");
        DataSource ds = jds;

        Path migrate = Paths.get("src/main/resources/db/migration");
        String locations = Files.isDirectory(migrate)
                ? "filesystem:" + migrate.toAbsolutePath()
                : "classpath:db/migration";

        // 保持连接存活，避免内存库在 Flyway 内部连接关闭后被回收
        try (Connection keepAlive = ds.getConnection()) {
            Flyway flyway = Flyway.configure()
                    .dataSource(ds)
                    .locations(locations)
                    .load();
            flyway.migrate();

            assertTableExists(ds, "project");
            assertTableExists(ds, "sdk_version");
            assertTableExists(ds, "sdk_install_record");
            assertTableExists(ds, "task");
            assertTableExists(ds, "task_step");
            assertTableExists(ds, "command_execution");
            assertTableExists(ds, "audit_log");
            assertTableExists(ds, "flyway_schema_history");
        }
    }

    private static void assertTableExists(DataSource ds, String table) throws Exception {
        try (Connection conn = ds.getConnection();
             java.sql.ResultSet rs = conn.getMetaData().getTables(null, null, "%", null)) {
            boolean found = false;
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name != null && name.equalsIgnoreCase(table)) {
                    found = true;
                    break;
                }
            }
            assertThat(found).as("表 %s 应存在", table).isTrue();
        }
    }
}
