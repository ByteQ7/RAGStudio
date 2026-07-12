package com.byteq.ai.ragstudio.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.byteq.ai.ragstudio.user.controller.request.ChangePasswordRequest;
import com.byteq.ai.ragstudio.user.controller.request.UserCreateRequest;
import com.byteq.ai.ragstudio.user.controller.request.UserPageRequest;
import com.byteq.ai.ragstudio.user.controller.request.UserUpdateRequest;
import com.byteq.ai.ragstudio.user.controller.vo.CurrentUserVO;
import com.byteq.ai.ragstudio.user.controller.vo.UserVO;
import com.byteq.ai.ragstudio.framework.context.LoginUser;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.framework.web.Results;
import com.byteq.ai.ragstudio.rag.constant.RAGConstant;
import com.byteq.ai.ragstudio.rag.dto.StoredFileDTO;
import com.byteq.ai.ragstudio.rag.service.FileStorageService;
import com.byteq.ai.ragstudio.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户管理控制器
 * <p>
 * 提供当前登录用户信息查询、用户 CRUD 管理以及密码修改等 RESTful 接口。
 * 用户管理类接口（如分页查询、创建、更新、删除）需要 admin 角色权限，
 * 通过 Sa-Token 的 {@link StpUtil#checkRole} 进行权限校验。
 * </p>
 *
 * @see UserService
 * @see UserContext
 * @see StpUtil
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final FileStorageService fileStorageService;

    /**
     * 获取当前登录用户信息
     * <p>
     * 从 {@link UserContext} 线程上下文中获取当前登录用户的基本信息，
     * 包括用户ID、用户名、角色和头像地址，封装为 {@link CurrentUserVO} 返回。
     * </p>
     *
     * @return 当前登录用户的基本信息
     */
    @GetMapping("/user/me")
    public Result<CurrentUserVO> currentUser() {
        LoginUser user = UserContext.requireUser();
        String avatar = user.getAvatar();
        // 将 s3:// 内部 URL 转换为前端可访问的 HTTP URL
        if (avatar != null && avatar.startsWith("s3://")) {
            try {
                avatar = fileStorageService.generatePresignedGetUrl(avatar);
            } catch (Exception e) {
                log.warn("转换头像 URL 失败: {}", avatar, e);
            }
        }
        return Results.success(new CurrentUserVO(
                user.getUserId(),
                user.getUsername(),
                user.getRole(),
                avatar
        ));
    }

    /**
     * 分页查询用户列表
     * <p>
     * 需要 admin 角色权限。根据请求参数中的分页和过滤条件，
     * 查询系统用户列表并返回分页结果。
     * </p>
     *
     * @param requestParam 分页查询参数，包含页码、每页大小及可选的过滤条件
     * @return 用户分页列表
     */
    @GetMapping("/users")
    public Result<IPage<UserVO>> pageQuery(UserPageRequest requestParam) {
        StpUtil.checkRole("admin");
        return Results.success(userService.pageQuery(requestParam));
    }

    /**
     * 创建用户
     * <p>
     * 需要 admin 角色权限。接收用户注册信息，创建新用户并返回用户ID。
     * </p>
     *
     * @param requestParam 用户创建请求体，包含用户名、密码、角色等信息
     * @return 统一响应结果，data 字段为新创建用户的ID
     */
    @PostMapping("/users")
    public Result<String> create(@RequestBody UserCreateRequest requestParam) {
        StpUtil.checkRole("admin");
        return Results.success(userService.create(requestParam));
    }

    /**
     * 更新用户信息
     * <p>
     * 需要 admin 角色权限。根据用户ID更新指定用户的详细信息。
     * </p>
     *
     * @param id          要更新的用户ID
     * @param requestParam 用户更新请求体，包含需要修改的字段
     * @return 统一响应结果
     */
    @PutMapping("/users/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody UserUpdateRequest requestParam) {
        StpUtil.checkRole("admin");
        userService.update(id, requestParam);
        return Results.success();
    }

    /**
     * 删除用户
     * <p>
     * 需要 admin 角色权限。根据用户ID删除指定用户。
     * </p>
     *
     * @param id 要删除的用户ID
     * @return 统一响应结果
     */
    @DeleteMapping("/users/{id}")
    public Result<Void> delete(@PathVariable String id) {
        StpUtil.checkRole("admin");
        userService.delete(id);
        return Results.success();
    }

    /**
     * 修改当前登录用户的密码
     * <p>
     * 当前登录用户修改自己的密码，需要提供旧密码进行身份验证。
     * 此接口不需要 admin 角色权限，任何已登录用户均可调用。
     * </p>
     *
     * @param requestParam 密码修改请求体，包含旧密码和新密码
     * @return 统一响应结果
     */
    @PutMapping("/user/password")
    public Result<Void> changePassword(@RequestBody ChangePasswordRequest requestParam) {
        userService.changePassword(requestParam);
        return Results.success();
    }

    /**
     * 上传当前登录用户头像
     * <p>
     * 将头像文件上传到 S3 ragstudio/user-img/ 目录，并更新当前用户的 avatar 字段。
     * </p>
     *
     * @param file 头像文件
     * @return 新的头像 URL
     */
    @PostMapping("/user/avatar")
    public Result<Map<String, String>> uploadAvatar(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new ClientException("上传文件不能为空");
        }

        LoginUser loginUser = UserContext.requireUser();
        StoredFileDTO stored = fileStorageService.upload(
                RAGConstant.S3_BUCKET_NAME,
                RAGConstant.S3_USER_AVATAR_PREFIX,
                file);

        // 存入数据库的 s3:// 内部 URL
        String s3Url = stored.getUrl();
        userService.updateAvatar(loginUser.getUserId(), s3Url);

        // 返回前端可访问的 HTTP URL
        String httpUrl = fileStorageService.generatePresignedGetUrl(s3Url);
        return Results.success(Map.of("avatarUrl", httpUrl));
    }
}
