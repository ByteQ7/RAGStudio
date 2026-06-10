package com.byteq.ai.ragstudio.user.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import com.byteq.ai.ragstudio.rag.config.DemoModeInterceptor;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 认证与拦截器配置类
 * <p>
 * 实现 {@link WebMvcConfigurer} 接口，配置系统的拦截器链，
 * 包括 Sa-Token 登录拦截、体验环境只读模式拦截和用户上下文填充拦截。
 * </p>
 *
 * <p>
 * <b>登录验证流程：</b>
 * <ol>
 *   <li>所有请求（除 /auth/** 和 /error 外）均需经过 Sa-Token 登录检查</li>
 *   <li>异步调度请求（SSE 完成回调的 asyncDispatch）跳过登录检查，避免上下文丢失问题</li>
 *   <li>OPTIONS 预检请求直接放行，避免 CORS 被拦截</li>
 *   <li>登录检查通过后，由 {@link UserContextInterceptor} 填充用户上下文</li>
 * </ol>
 * </p>
 *
 * @see UserContextInterceptor
 * @see DemoModeInterceptor
 */
@Configuration
@RequiredArgsConstructor
public class SaTokenConfig implements WebMvcConfigurer {

    /**
     * 体验环境只读模式拦截器
     */
    private final DemoModeInterceptor demoModeInterceptor;

    /**
     * 用户上下文拦截器
     */
    private final UserContextInterceptor userContextInterceptor;

    /**
     * 添加拦截器配置
     *
     * @param registry 拦截器注册器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册 SaToken 登录拦截器
        registry.addInterceptor(new SaInterceptor(handler -> {
                    // 异步调度请求跳过登录检查（SSE 完成回调会触发 asyncDispatch，此时 SaToken 上下文已丢失）
                    ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                    if (attrs != null) {
                        HttpServletRequest request = attrs.getRequest();
                        // 判断是否为异步调度请求，如果是则跳过登录检查
                        if (request.getDispatcherType() == DispatcherType.ASYNC) {
                            return;
                        }
                        // 预检请求直接放行，避免 CORS 被拦截
                        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                            return;
                        }
                    }
                    // 执行登录检查
                    StpUtil.checkLogin();
                }))
                // 拦截所有路径
                .addPathPatterns("/**")
                // 排除认证相关路径和错误页面
                .excludePathPatterns("/auth/**", "/error");

        // 注册体验环境只读模式拦截器
        registry.addInterceptor(demoModeInterceptor)
                // 拦截所有路径
                .addPathPatterns("/**")
                // 排除认证相关路径和错误页面
                .excludePathPatterns("/auth/**", "/error");

        // 注册用户上下文拦截器
        registry.addInterceptor(userContextInterceptor)
                // 拦截所有路径
                .addPathPatterns("/**")
                // 排除认证相关路径和错误页面
                .excludePathPatterns("/auth/**", "/error");
    }
}
