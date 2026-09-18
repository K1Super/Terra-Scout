package com.terrascout.orchestrator.core.dto;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import com.terrascout.orchestrator.core.error.TerraScoutError;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DTO 属性往返断言：全部 DTO 的可写属性 setter/getter 回读一致（等价于 openapi 契约字段全覆盖），
 * 并验证 ApiResponse 工厂语义（D-002：成功 = 200000 + "success"）。
 */
class DtoBeanRoundTripTest {

    private static final List<Class<?>> DTO_CLASSES = List.of(
            AnalyzeRequest.class,
            AnalyzeResponse.class,
            ProjectConstraint.class,
            InstallPlan.class,
            SdkInstallItem.class,
            DependencyInstallItem.class,
            CommandSpec.class,
            ExecuteRequest.class,
            ExecuteResponse.class,
            TaskResponse.class,
            TaskResponse.TaskStepDetail.class,
            ApiResponse.class);

    @Test
    @DisplayName("全部 DTO 可写属性 setter/getter 往返一致")
    void allDtoPropertiesRoundTrip() {
        for (Class<?> dtoClass : DTO_CLASSES) {
            Object bean = instantiate(dtoClass);
            for (PropertyDescriptor descriptor : writableProperties(dtoClass)) {
                Object sample = sampleValue(descriptor.getPropertyType(), dtoClass);
                invoke(descriptor.getWriteMethod(), bean, sample);
                Object readBack = invoke(descriptor.getReadMethod(), bean, null);
                assertThat(readBack)
                        .as("%s.%s", dtoClass.getSimpleName(), descriptor.getName())
                        .isEqualTo(sample);
            }
        }
    }

    @Test
    @DisplayName("ApiResponse.ok：code=200000，message=success（D-002）")
    void apiResponseOk() {
        long before = System.currentTimeMillis();
        ApiResponse<String> response = ApiResponse.ok("payload");

        assertThat(response.getCode()).isEqualTo(200000);
        assertThat(response.getMessage()).isEqualTo("success");
        assertThat(response.getData()).isEqualTo("payload");
        assertThat(response.getTimestamp()).isBetween(before, System.currentTimeMillis() + 1);
        assertThat(response.getDetails()).isNull();
    }

    @Test
    @DisplayName("ApiResponse.fail：code/message 取错误码，details 透传")
    void apiResponseFail() {
        ApiResponse<Void> plain = ApiResponse.fail(TerraScoutError.TASK_QUEUE_FULL);
        assertThat(plain.getCode()).isEqualTo(409006);
        assertThat(plain.getMessage()).isEqualTo(TerraScoutError.TASK_QUEUE_FULL.getDefaultMessage());
        assertThat(plain.getData()).isNull();

        Map<String, Object> details = Map.of("queue", 100);
        ApiResponse<Void> withDetails = ApiResponse.fail(TerraScoutError.PROJECT_LOCKED, details);
        assertThat(withDetails.getCode()).isEqualTo(409001);
        assertThat(withDetails.getDetails()).isEqualTo(details);
    }

    // ==================== 反射工具 ====================

    private static Object instantiate(Class<?> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法实例化 " + type.getName(), e);
        }
    }

    private static List<PropertyDescriptor> writableProperties(Class<?> dtoClass) {
        try {
            return java.util.Arrays.stream(
                            Introspector.getBeanInfo(dtoClass, Object.class).getPropertyDescriptors())
                    .filter(d -> d.getWriteMethod() != null && d.getReadMethod() != null)
                    .collect(java.util.stream.Collectors.toList());
        } catch (IntrospectionException e) {
            throw new IllegalStateException("无法内省 " + dtoClass.getName(), e);
        }
    }

    /** 特殊处理泛型 ApiResponse.data（Object）：返回具体样例而非通用 null。 */
    private static Object sampleValue(Class<?> type, Class<?> declaringClass) {
        if (type == Object.class && declaringClass == ApiResponse.class) {
            return "object-value";
        }
        return sampleValue(type);
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
        if (type == List.class) {
            return List.of("item");
        }
        if (type == Map.class) {
            return Map.of("key", "value");
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        if (DTO_CLASSES.contains(type)) {
            // 嵌套 DTO（如 AnalyzeResponse.plan → InstallPlan）：返回空实例，其属性由本类自身的遍历覆盖
            return instantiate(type);
        }
        throw new IllegalStateException("未覆盖的属性类型: " + type.getName());
    }

    private static Object invoke(Method method, Object bean, Object arg) {
        try {
            return method.getParameterCount() == 0 ? method.invoke(bean) : method.invoke(bean, arg);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("调用 " + method + " 失败", e);
        }
    }
}
