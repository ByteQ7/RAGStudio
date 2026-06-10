package com.byteq.ai.ragstudio.framework.config;

import com.byteq.ai.ragstudio.framework.web.GlobalExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Web 组件自动装配类
 *
 * <p>自动装配 Web 层所需的基础组件，当前装配了全局异常处理器。
 * 业务模块通过引入该配置即可获得统一的异常处理能力，
 * 无需在每个服务中重复定义。</p>
 */
@Configuration
public class WebAutoConfiguration {

    /**
     * 构建全局异常拦截器组件 Bean
     * <p>注册 {@link GlobalExceptionHandler} 到 Spring 容器，使其能够通过
     * {@code @RestControllerAdvice} 拦截并统一处理所有 Controller 层异常。</p>
     *
     * @return 全局异常处理器实例
     */
    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
