package com.terrascout.orchestrator.core.domain;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.Column;
import jakarta.persistence.Table;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体与 DDL 逐列同步断言。
 *
 * <p>ddl-auto: validate 只校验存在性与类型，<b>不校验列名拼写</b>——本测试补上这道防线：
 * 每个实体的 @Table 名与 @Column 名集合必须与 V1__init.sql 基线完全一致，
 * 任何一侧漂移（实体加字段忘写迁移 / 迁移改名忘改实体）立即暴露。
 */
class DomainEntitySyncTest {

    /** 与 V1__init.sql 逐列对照的权威清单。 */
    private static List<Class<?>> entities() {
        return List.of(Project.class, SdkVersion.class, SdkInstallRecord.class,
                Task.class, TaskStep.class, CommandExecution.class, AuditLog.class);
    }

    private static String tableName(Class<?> entity) {
        Table table = entity.getAnnotation(Table.class);
        assertThat(table).as("%s 缺少 @Table", entity.getSimpleName()).isNotNull();
        return table.name();
    }

    /** 收集实体全部显式 @Column 名（本工程约定：字段一律显式注解，不依赖 Hibernate 隐式命名）。 */
    private static Set<String> columnNames(Class<?> entity) {
        return Arrays.stream(entity.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(Column.class))
                .map(f -> f.getAnnotation(Column.class).name())
                .collect(Collectors.toSet());
    }

    private static void assertColumns(Class<?> entity, Set<String> expected) {
        Set<String> actual = columnNames(entity);
        assertThat(actual)
                .as("%s 的 @Column 集合应与 DDL 一致", entity.getSimpleName())
                .containsExactlyInAnyOrderElementsOf(expected);
        // 字段数 == 显式列注解数（防漏注解字段走隐式命名）
        long fieldCount = Arrays.stream(entity.getDeclaredFields())
                .filter(f -> !f.getName().startsWith("$"))
                .count();
        assertThat(fieldCount)
                .as("%s 字段数应与显式列注解数一致", entity.getSimpleName())
                .isEqualTo(actual.size());
    }

    @Test
    @DisplayName("表名与 V1__init.sql 一致")
    void tableNamesMatchDdl() {
        assertThat(tableName(Project.class)).isEqualTo("project");
        assertThat(tableName(SdkVersion.class)).isEqualTo("sdk_version");
        assertThat(tableName(SdkInstallRecord.class)).isEqualTo("sdk_install_record");
        assertThat(tableName(Task.class)).isEqualTo("task");
        assertThat(tableName(TaskStep.class)).isEqualTo("task_step");
        assertThat(tableName(CommandExecution.class)).isEqualTo("command_execution");
        assertThat(tableName(AuditLog.class)).isEqualTo("audit_log");
    }

    @Test
    @DisplayName("project 列集合与 DDL 一致")
    void projectColumns() {
        assertColumns(Project.class, Set.of(
                "id", "name", "project_root_path", "os_type", "profile_json", "created_at", "updated_at"));
    }

    @Test
    @DisplayName("sdk_version 列集合与 DDL 一致（全字段）")
    void sdkVersionColumns() {
        assertColumns(SdkVersion.class, Set.of(
                "id", "language", "version", "os", "arch", "download_url", "sha256", "size_bytes",
                "lts", "eol", "eol_date", "cve_count", "highest_cve_severity",
                "license", "distribution", "vendor", "release_time", "created_at"));
    }

    @Test
    @DisplayName("sdk_install_record 列集合与 DDL 一致")
    void sdkInstallRecordColumns() {
        assertColumns(SdkInstallRecord.class, Set.of(
                "id", "language", "version", "install_path", "scope", "project_id", "status",
                "checksum", "installed_at"));
    }

    @Test
    @DisplayName("task 列集合与 DDL 一致")
    void taskColumns() {
        assertColumns(Task.class, Set.of(
                "id", "parent_id", "task_type", "status", "payload_json", "progress",
                "retry_count", "max_retry", "lock_owner", "heartbeat_at",
                "error_code", "error_msg", "idempotency_key", "created_at", "updated_at"));
    }

    @Test
    @DisplayName("task_step 列集合与 DDL 一致")
    void taskStepColumns() {
        assertColumns(TaskStep.class, Set.of(
                "id", "task_id", "step_index", "step_name", "status",
                "input_json", "output_json", "rollback_json",
                "error_code", "error_msg", "started_at", "finished_at", "retry_count"));
    }

    @Test
    @DisplayName("command_execution 列集合与 DDL 一致")
    void commandExecutionColumns() {
        assertColumns(CommandExecution.class, Set.of(
                "id", "project_id", "command", "args_json", "work_dir", "env_json",
                "exit_code", "stdout_file", "stderr_file", "status", "started_at", "finished_at"));
    }

    @Test
    @DisplayName("audit_log 列集合与 DDL 一致（seq 为 IDENTITY）")
    void auditLogColumns() {
        assertColumns(AuditLog.class, Set.of(
                "seq", "biz_id", "action", "target_type", "target_id",
                "before_json", "after_json", "result", "prev_hash", "hash", "created_at"));
        Field seq = Arrays.stream(AuditLog.class.getDeclaredFields())
                .filter(f -> f.getName().equals("seq"))
                .findFirst().orElseThrow();
        assertThat(seq.isAnnotationPresent(jakarta.persistence.Id.class)).isTrue();
        assertThat(seq.isAnnotationPresent(jakarta.persistence.GeneratedValue.class)).isTrue();
    }

    /** 全部实体 JavaBean 属性 setter/getter 回读一致；只读属性（如 AuditLog.seq）至少读取一次。 */
    @Test
    @DisplayName("全部实体可写属性往返一致，只读属性可读取")
    void allEntityPropertiesRoundTrip() {
        for (Class<?> entity : entities()) {
            Object bean = instantiate(entity);
            for (java.beans.PropertyDescriptor descriptor : beanProperties(entity)) {
                if (descriptor.getWriteMethod() != null) {
                    Object sample = sampleValue(descriptor.getPropertyType());
                    try {
                        descriptor.getWriteMethod().invoke(bean, sample);
                        Object readBack = descriptor.getReadMethod().invoke(bean);
                        assertThat(readBack)
                                .as("%s.%s", entity.getSimpleName(), descriptor.getName())
                                .isEqualTo(sample);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException("反射调用失败: " + descriptor, e);
                    }
                } else if (descriptor.getReadMethod() != null) {
                    try {
                        descriptor.getReadMethod().invoke(bean);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException("反射读取失败: " + descriptor, e);
                    }
                }
            }
        }
    }

    private static Object instantiate(Class<?> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法实例化 " + type.getName(), e);
        }
    }

    private static List<java.beans.PropertyDescriptor> beanProperties(Class<?> entity) {
        try {
            return Arrays.stream(java.beans.Introspector
                            .getBeanInfo(entity, Object.class).getPropertyDescriptors())
                    .collect(Collectors.toList());
        } catch (java.beans.IntrospectionException e) {
            throw new IllegalStateException("无法内省 " + entity.getName(), e);
        }
    }

    private static Object sampleValue(Class<?> type) {
        if (type == String.class) {
            return "sample";
        }
        if (type == boolean.class || type == Boolean.class) {
            return Boolean.TRUE;
        }
        if (type == int.class || type == Integer.class) {
            return 42;
        }
        if (type == long.class || type == Long.class) {
            return 42L;
        }
        if (type == double.class || type == Double.class) {
            return 0.5d;
        }
        if (type == LocalDate.class) {
            return LocalDate.of(2026, 1, 1);
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        throw new IllegalStateException("未覆盖的属性类型: " + type.getName());
    }
}
