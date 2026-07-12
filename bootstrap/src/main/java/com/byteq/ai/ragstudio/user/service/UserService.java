package com.byteq.ai.ragstudio.user.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.byteq.ai.ragstudio.user.controller.request.ChangePasswordRequest;
import com.byteq.ai.ragstudio.user.controller.request.UserCreateRequest;
import com.byteq.ai.ragstudio.user.controller.request.UserPageRequest;
import com.byteq.ai.ragstudio.user.controller.request.UserUpdateRequest;
import com.byteq.ai.ragstudio.user.controller.vo.UserVO;

/**
 * 用户管理服务接口
 * <p>
 * 定义用户管理的核心业务逻辑，包括用户分页查询、创建、更新、删除以及密码修改功能。
 * 用户管理操作通常需要 admin 角色权限，密码修改则面向所有已登录用户。
 * </p>
 *
 * @see UserController
 * @see UserVO
 */
public interface UserService {

    /**
     * 分页查询用户列表
     * <p>
     * 根据分页参数和过滤条件，查询系统用户列表。支持按用户名、邮箱等条件进行筛选。
     * </p>
     *
     * @param requestParam 分页查询参数，包含页码、每页大小及过滤条件
     * @return 用户分页列表
     */
    IPage<UserVO> pageQuery(UserPageRequest requestParam);

    /**
     * 创建用户
     * <p>
     * 接收用户注册信息，创建新用户。密码会经过加密处理后存储。
     * </p>
     *
     * @param requestParam 用户创建请求参数，包含用户名、密码、角色等信息
     * @return 新创建用户的唯一标识ID
     */
    String create(UserCreateRequest requestParam);

    /**
     * 更新用户信息
     * <p>
     * 根据用户ID更新指定用户的详细信息，如用户名、邮箱、角色等。
     * </p>
     *
     * @param id           要更新的用户ID
     * @param requestParam 用户更新请求参数，包含需要修改的字段
     */
    void update(String id, UserUpdateRequest requestParam);

    /**
     * 删除用户
     * <p>
     * 根据用户ID删除指定用户。删除后该用户将无法登录系统。
     * </p>
     *
     * @param id 要删除的用户ID
     */
    void delete(String id);

    /**
     * 修改当前登录用户的密码
     * <p>
     * 需要提供旧密码进行身份验证，验证通过后更新为新密码。
     * 如果旧密码不正确，将抛出业务异常。
     * </p>
     *
     * @param requestParam 密码修改请求参数，包含旧密码和新密码
     */
    void changePassword(ChangePasswordRequest requestParam);

    /**
     * 上传并更新当前用户头像
     * <p>
     * 将头像文件上传到 S3，更新当前用户的 avatar 字段。
     * </p>
     *
     * @param userId   用户 ID
     * @param iconUrl  头像文件 URL
     */
    void updateAvatar(String userId, String iconUrl);
}
