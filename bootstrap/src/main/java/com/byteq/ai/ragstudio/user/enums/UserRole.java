package com.byteq.ai.ragstudio.user.enums;

import com.byteq.ai.ragstudio.user.constant.RoleConstant;
import lombok.Getter;

/**
 * 用户角色枚举
 * <p>
 * 定义系统中的用户角色类型，角色编码统一来自 {@link RoleConstant}，
 * 该枚举用于领域层角色取值与规范化，Web 层鉴权注解请引用 {@link RoleConstant}。
 * </p>
 */
@Getter
public enum UserRole {

    /**
     * 管理员角色
     */
    ADMIN(RoleConstant.ADMIN),

    /**
     * 普通用户角色
     */
    USER(RoleConstant.USER);

    /**
     * 角色编码
     */
    private final String code;

    /**
     * 构造函数
     *
     * @param code 角色编码
     */
    UserRole(String code) {
        this.code = code;
    }
}
