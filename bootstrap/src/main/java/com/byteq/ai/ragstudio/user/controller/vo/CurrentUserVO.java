package com.byteq.ai.ragstudio.user.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前登录用户信息视图对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CurrentUserVO {

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 用户名
     */
    private String username;

    /**
     * 角色
     */
    private String role;

    /**
     * 头像地址
     */
    private String avatar;
}
