package com.byteq.ai.ragstudio.framework.errorcode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 错误码注册表唯一性校验
 *
 * <p>扫描 {@code com.byteq.ai.ragstudio} 下所有 {@link IErrorCode} 实现，保证：</p>
 * <ul>
 *   <li>错误码全局唯一（含框架层 {@link BaseErrorCode} 与各业务域枚举）</li>
 *   <li>错误码格式合法：{@code [ABC] + 6 位数字}</li>
 *   <li>错误消息非空</li>
 *   <li>业务域枚举禁止占用框架层码段（A000xxx/B000xxx/C000xxx）</li>
 * </ul>
 *
 * <p>新增错误码枚举无需修改本测试，自动纳入校验；撞码、格式错误将导致构建失败，
 * 从而保证新扩展不影响原有错误码体系。</p>
 */
class ErrorCodeRegistryTest {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[ABC]\\d{6}$");

    /**
     * 框架层码段：A000xxx / B000xxx / C000xxx（BaseErrorCode 专用，业务域禁止占用）
     */
    private static final Pattern FRAMEWORK_CODE_PATTERN = Pattern.compile("^[ABC]000\\d{3}$");

    @Test
    void allErrorCodesShouldBeUniqueAndValid() throws Exception {
        List<Class<? extends IErrorCode>> implementations = scanErrorCodeClasses();
        assertFalse(implementations.isEmpty(), "应至少扫描到一个 IErrorCode 实现");

        Set<String> codes = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (Class<? extends IErrorCode> clazz : implementations) {
            IErrorCode[] constants = clazz.getEnumConstants();
            if (constants == null) {
                continue;
            }
            for (IErrorCode errorCode : constants) {
                String code = errorCode.code();
                assertTrue(CODE_PATTERN.matcher(code).matches(),
                        clazz.getSimpleName() + "." + errorCode + " 错误码格式非法: " + code);
                assertFalse(errorCode.message().isBlank(),
                        clazz.getSimpleName() + "." + errorCode + " 错误消息为空");
                if (!codes.add(code)) {
                    duplicates.add(code);
                }
            }
        }
        assertEquals(List.of(), duplicates, "存在重复错误码: " + duplicates);
    }

    @Test
    void businessDomainsShouldNotOccupyFrameworkSegment() throws Exception {
        for (Class<? extends IErrorCode> clazz : scanErrorCodeClasses()) {
            if (clazz == BaseErrorCode.class) {
                continue;
            }
            for (IErrorCode errorCode : clazz.getEnumConstants()) {
                String code = errorCode.code();
                assertFalse(FRAMEWORK_CODE_PATTERN.matcher(code).matches(),
                        clazz.getSimpleName() + "." + errorCode + " 占用框架层码段 (A000xxx/B000xxx/C000xxx): "
                                + code + "，请改用业务域码段");
            }
        }
    }

    @Test
    void frameworkCodePatternShouldDetectFrameworkSegment() {
        assertTrue(FRAMEWORK_CODE_PATTERN.matcher("A000999").matches());
        assertTrue(FRAMEWORK_CODE_PATTERN.matcher("B000000").matches());
        assertTrue(FRAMEWORK_CODE_PATTERN.matcher("C000123").matches());
        assertFalse(FRAMEWORK_CODE_PATTERN.matcher("A001001").matches());
        assertFalse(FRAMEWORK_CODE_PATTERN.matcher("A100000").matches());
    }

    @SuppressWarnings("unchecked")
    private List<Class<? extends IErrorCode>> scanErrorCodeClasses() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(IErrorCode.class));
        List<Class<? extends IErrorCode>> result = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.byteq.ai.ragstudio")) {
            Class<?> clazz = Class.forName(definition.getBeanClassName());
            if (clazz.isEnum() && IErrorCode.class.isAssignableFrom(clazz)) {
                result.add((Class<? extends IErrorCode>) clazz);
            }
        }
        return result;
    }
}
