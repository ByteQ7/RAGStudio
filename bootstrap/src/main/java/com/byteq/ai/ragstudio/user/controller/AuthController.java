package com.byteq.ai.ragstudio.user.controller;

import com.byteq.ai.ragstudio.user.controller.request.LoginRequest;
import com.byteq.ai.ragstudio.user.controller.vo.LoginVO;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.web.Results;
import com.byteq.ai.ragstudio.user.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器
 * <p>
 * 处理用户登录和登出相关的HTTP请求，提供基于 Sa-Token 的用户认证能力。
 * 登录成功后返回包含 Token 的 {@link LoginVO}，后续请求通过 Token 进行身份验证。
 * </p>
 *
 * @see AuthService
 * @see LoginRequest
 * @see LoginVO
 */
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 用户登录
     * <p>
     * 接收用户名和密码，调用 {@link AuthService#login(LoginRequest)} 进行身份校验。
     * 认证成功后返回 {@link LoginVO}，其中包含 Sa-Token 生成的登录 Token，
     * 前端需在后续请求的 Header 中携带该 Token。
     * </p>
     *
     * @param requestParam 登录请求体，包含用户名和密码
     * @return 统一响应结果，data 字段为登录成功后返回的 Token 及用户信息
     */
    @PostMapping("/auth/login")
    public Result<LoginVO> login(@RequestBody LoginRequest requestParam) {
        return Results.success(authService.login(requestParam));
    }

    /**
     * 用户登出
     * <p>
     * 调用 {@link AuthService#logout()} 清除当前登录用户的 Sa-Token 会话，
     * 使当前 Token 失效，后续请求需重新登录。
     * </p>
     *
     * @return 统一响应结果
     */
    @PostMapping("/auth/logout")
    public Result<Void> logout() {
        authService.logout();
        return Results.success();
    }
}
