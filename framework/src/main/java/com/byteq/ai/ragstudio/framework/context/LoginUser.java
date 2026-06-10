package com.byteq.ai.ragstudio.framework.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前登录用户的上下文快照
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginUser {

    /**
     * 用户唯一标识
     */
    private String userId;

    /**
     * 用户登录名
     */
    private String username;

    /**
     * 用户角色标识
     * <p>如 {@code "admin"} 表示管理员，{@code "user"} 表示普通用户等</p>
     */
    private String role;

    /**
     * 用户头像 URL
     */
    private String avatar;
}
