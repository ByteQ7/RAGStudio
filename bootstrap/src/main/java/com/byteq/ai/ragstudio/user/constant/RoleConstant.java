package com.byteq.ai.ragstudio.user.constant;

import com.byteq.ai.ragstudio.user.enums.UserRole;

/**
 * 角色常量
 * <p>
 * 与 {@link UserRole} 枚举一一对应，作为编译期常量供 {@code @SaCheckRole}
 * 等 Sa-Token 注解使用（注解属性要求编译期常量表达式，无法使用方法返回值）。
 * </p>
 *
 * <p>
 * 全系统角色只有两类：
 * <ul>
 *   <li>{@link #ADMIN}：管理员，可访问全部对话能力与管理后台</li>
 *   <li>{@link #USER}：普通用户，仅可使用对话与会话等个人能力</li>
 * </ul>
 * 禁止在业务代码中硬编码角色字符串，统一引用本类常量。
 * </p>
 *
 * @see UserRole
 */
public final class RoleConstant {

    /**
     * 管理员角色编码
     */
    public static final String ADMIN = "admin";

    /**
     * 普通用户角色编码
     */
    public static final String USER = "user";

    private RoleConstant() {
    }
}
