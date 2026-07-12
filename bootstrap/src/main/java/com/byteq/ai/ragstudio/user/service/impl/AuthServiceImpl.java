package com.byteq.ai.ragstudio.user.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.byteq.ai.ragstudio.user.controller.request.LoginRequest;
import com.byteq.ai.ragstudio.user.controller.vo.LoginVO;
import com.byteq.ai.ragstudio.user.dao.entity.UserDO;
import com.byteq.ai.ragstudio.user.dao.mapper.UserMapper;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.framework.security.PasswordHasher;
import com.byteq.ai.ragstudio.rag.service.FileStorageService;
import com.byteq.ai.ragstudio.user.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 认证服务实现类
 * <p>
 * 实现用户登录和登出的核心业务逻辑，包括身份校验、Sa-Token 会话管理和登录信息返回。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String DEFAULT_AVATAR_URL = "https://avatars.githubusercontent.com/u/583231?v=4";

    private final UserMapper userMapper;
    private final FileStorageService fileStorageService;

    /**
     * 用户登录
     * <p>
     * 处理流程：
     * 1. 校验用户名和密码非空
     * 2. 根据用户名查询未删除的用户记录
     * 3. 校验密码是否匹配
     * 4. 调用 Sa-Token 执行登录，生成会话 Token
     * 5. 返回包含 Token 和用户信息的 LoginVO
     * </p>
     *
     * @param requestParam 登录请求参数，包含用户名和密码
     * @return 登录响应结果，包含 Token、角色和头像信息
     * @throws ClientException 用户名/密码为空、用户不存在或密码错误时抛出
     */
    @Override
    public LoginVO login(LoginRequest requestParam) {
        String username = requestParam.getUsername();
        String password = requestParam.getPassword();
        if (StrUtil.isBlank(username) || StrUtil.isBlank(password)) {
            throw new ClientException("用户名或密码不能为空");
        }
        UserDO user = findByUsername(username);
        if (user == null || !PasswordHasher.matches(password, user.getPassword())) {
            throw new ClientException("用户名或密码错误");
        }
        if (user.getId() == null) {
            throw new ClientException("用户信息异常");
        }
        String loginId = user.getId().toString();
        StpUtil.login(loginId);
        String avatar = user.getAvatar();
        if (StrUtil.isBlank(avatar)) {
            avatar = DEFAULT_AVATAR_URL;
        } else if (avatar.startsWith("s3://")) {
            try {
                avatar = fileStorageService.generatePresignedGetUrl(avatar);
            } catch (Exception e) {
                log.warn("转换登录头像 URL 失败", e);
                avatar = DEFAULT_AVATAR_URL;
            }
        }
        return new LoginVO(loginId, user.getRole(), StpUtil.getTokenValue(), avatar);
    }

    @Override
    public void logout() {
        StpUtil.logout();
    }

    // 根据用户名查询未删除的用户记录，用户名为空时返回 null
    private UserDO findByUsername(String username) {
        if (StrUtil.isBlank(username)) {
            return null;
        }
        return userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getUsername, username)
                        .eq(UserDO::getDeleted, 0)
        );
    }
}
