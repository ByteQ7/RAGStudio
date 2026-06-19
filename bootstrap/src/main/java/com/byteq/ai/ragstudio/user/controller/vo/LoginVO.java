package com.byteq.ai.ragstudio.user.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录响应视图对象，包含 Token 和用户基本信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginVO {

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 角色
     */
    private String role;

    /**
     * Sa-Token 登录令牌
     */
    private String token;

    /**
     * 头像地址
     */
    private String avatar;
}
