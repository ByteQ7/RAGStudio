package com.byteq.ai.ragstudio.user.service;

import com.byteq.ai.ragstudio.user.controller.request.LoginRequest;
import com.byteq.ai.ragstudio.user.controller.vo.LoginVO;

/**
 * 认证服务接口
 * <p>
 * 定义用户认证相关的核心业务逻辑，包括登录和登出功能。
 * 登录成功后通过 Sa-Token 生成登录会话，登出时清除会话信息。
 * </p>
 *
 * @see AuthController
 * @see LoginRequest
 * @see LoginVO
 */
public interface AuthService {

    /**
     * 用户登录
     * <p>
     * 根据用户名和密码进行身份验证，验证通过后生成 Sa-Token 登录会话，
     * 并返回包含 Token 和用户信息的 {@link LoginVO}。
     * </p>
     *
     * @param requestParam 登录请求参数，包含用户名和密码
     * @return 登录响应结果，包含 Token 和用户基本信息
     */
    LoginVO login(LoginRequest requestParam);

    /**
     * 用户登出
     * <p>
     * 清除当前登录用户的 Sa-Token 会话，使当前 Token 失效，
     * 后续请求需要重新登录才能访问受保护的接口。
     * </p>
     */
    void logout();
}
