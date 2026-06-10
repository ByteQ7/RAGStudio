package com.byteq.ai.ragstudio.framework.context;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.util.Map;

/**
 * Spring 应用上下文持有者
 *
 * <p>用于在非 Spring 管理的类中获取 Spring 容器中的 Bean 实例。
 * 通过实现 {@link ApplicationContextAware} 接口，在 Spring 容器启动时自动注入
 * {@link ApplicationContext}，并提供一系列静态工具方法供全局使用。</p>
 *
 * <p>典型使用场景：</p>
 * <ul>
 *   <li>在工具类、工厂类中获取 Spring Bean</li>
 *   <li>在框架组件中获取特定类型的全部 Bean 实现</li>
 *   <li>在无法使用依赖注入的场合（如 JPA 实体类监听器）中获取 Bean</li>
 * </ul>
 */
@Component
public class ApplicationContextHolder implements ApplicationContextAware {

    private static ApplicationContext CONTEXT;

    @Override
    public void setApplicationContext(@NonNull ApplicationContext applicationContext) throws BeansException {
        ApplicationContextHolder.CONTEXT = applicationContext;
    }

    /**
     * 根据类型获取 Bean
     */
    public static <T> T getBean(Class<T> clazz) {
        return CONTEXT.getBean(clazz);
    }

    /**
     * 根据名称获取 Bean
     */
    public static Object getBean(String name) {
        return CONTEXT.getBean(name);
    }

    /**
     * 根据名称和类型获取 Bean
     */
    public static <T> T getBean(String name, Class<T> clazz) {
        return CONTEXT.getBean(name, clazz);
    }

    /**
     * 根据类型获取同类型的所有 Bean
     */
    public static <T> Map<String, T> getBeansOfType(Class<T> clazz) {
        return CONTEXT.getBeansOfType(clazz);
    }

    /**
     * 查找 Bean 上的注解
     */
    public static <A extends Annotation> A findAnnotationOnBean(String beanName, Class<A> annotationType) {
        return CONTEXT.findAnnotationOnBean(beanName, annotationType);
    }

    /**
     * 获取 ApplicationContext
     */
    public static ApplicationContext getInstance() {
        return CONTEXT;
    }
}
