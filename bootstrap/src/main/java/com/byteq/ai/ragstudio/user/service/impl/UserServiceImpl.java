package com.byteq.ai.ragstudio.user.service.impl;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.byteq.ai.ragstudio.framework.context.LoginUser;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.framework.security.PasswordHasher;
import com.byteq.ai.ragstudio.user.controller.request.ChangePasswordRequest;
import com.byteq.ai.ragstudio.user.controller.request.UserCreateRequest;
import com.byteq.ai.ragstudio.user.controller.request.UserPageRequest;
import com.byteq.ai.ragstudio.user.controller.request.UserUpdateRequest;
import com.byteq.ai.ragstudio.user.controller.vo.UserVO;
import com.byteq.ai.ragstudio.user.dao.entity.UserDO;
import com.byteq.ai.ragstudio.user.dao.mapper.UserMapper;
import com.byteq.ai.ragstudio.user.enums.UserRole;
import com.byteq.ai.ragstudio.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 用户管理服务实现类
 * <p>
 * 实现用户的分页查询、创建、更新、删除及密码修改等业务逻辑，
 * 包含默认管理员保护、用户名唯一性校验和角色规范化等内部辅助方法。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final String DEFAULT_ADMIN_USERNAME = "admin";

    private final UserMapper userMapper;

    /**
     * 分页查询用户列表
     * <p>
     * 处理流程：
     * 1. 解析关键词过滤条件（支持模糊匹配用户名和角色）
     * 2. 构建分页查询条件，过滤已删除用户并按更新时间降序排列
     * 3. 将查询结果从 UserDO 转换为 UserVO 返回
     * </p>
     */
    @Override
    public IPage<UserVO> pageQuery(UserPageRequest requestParam) {
        String keyword = StrUtil.trimToNull(requestParam.getKeyword());
        Page<UserDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<UserDO> result = userMapper.selectPage(
                page,
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getDeleted, 0)
                        .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                                .like(UserDO::getUsername, keyword)
                                .or()
                                .like(UserDO::getRole, keyword))
                        .orderByDesc(UserDO::getUpdateTime)
        );
        return result.convert(this::toVO);
    }

    /**
     * 创建用户
     * <p>
     * 处理流程：
     * 1. 校验请求参数和用户名/密码非空
     * 2. 禁止使用默认管理员用户名 "admin"
     * 3. 规范化角色值，校验用户名唯一性
     * 4. 加密密码后构建 UserDO 并插入数据库
     * </p>
     */
    @Override
    public String create(UserCreateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String username = StrUtil.trimToNull(requestParam.getUsername());
        String password = StrUtil.trimToNull(requestParam.getPassword());
        String role = StrUtil.trimToNull(requestParam.getRole());
        Assert.notBlank(username, () -> new ClientException("用户名不能为空"));
        Assert.notBlank(password, () -> new ClientException("密码不能为空"));

        if (DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(username)) {
            throw new ClientException("默认管理员用户名不可用");
        }
        role = normalizeRole(role);
        ensureUsernameAvailable(username, null);

        UserDO record = UserDO.builder()
                .username(username)
                .password(PasswordHasher.hash(password))
                .role(role)
                .avatar(StrUtil.trimToNull(requestParam.getAvatar()))
                .build();
        userMapper.insert(record);
        return String.valueOf(record.getId());
    }

    /**
     * 更新用户信息
     * <p>
     * 处理流程：
     * 1. 加载目标用户并校验非默认管理员
     * 2. 按需更新用户名（校验唯一性和合法性）、角色、头像和密码
     * 3. 调用 Mapper 持久化更新
     * </p>
     */
    @Override
    public void update(String id, UserUpdateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        UserDO record = loadById(id);
        ensureNotDefaultAdmin(record);

        if (requestParam.getUsername() != null) {
            String username = StrUtil.trimToNull(requestParam.getUsername());
            Assert.notBlank(username, () -> new ClientException("用户名不能为空"));
            if (!username.equals(record.getUsername())) {
                if (DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(username)) {
                    throw new ClientException("默认管理员用户名不可用");
                }
                ensureUsernameAvailable(username, record.getId());
            }
            record.setUsername(username);
        }

        if (requestParam.getRole() != null) {
            record.setRole(normalizeRole(requestParam.getRole()));
        }

        if (requestParam.getAvatar() != null) {
            record.setAvatar(StrUtil.trimToNull(requestParam.getAvatar()));
        }

        if (requestParam.getPassword() != null) {
            String password = StrUtil.trimToNull(requestParam.getPassword());
            Assert.notBlank(password, () -> new ClientException("新密码不能为空"));
            record.setPassword(PasswordHasher.hash(password));
        }

        userMapper.updateById(record);
    }

    /**
     * 删除用户，默认管理员不允许删除
     */
    @Override
    public void delete(String id) {
        UserDO record = loadById(id);
        ensureNotDefaultAdmin(record);
        userMapper.deleteById(record.getId());
    }

    /**
     * 修改当前登录用户的密码
     * <p>
     * 处理流程：
     * 1. 校验当前密码和新密码非空
     * 2. 从 UserContext 获取当前用户并查询数据库记录
     * 3. 验证当前密码是否正确
     * 4. 加密新密码后更新数据库
     * </p>
     */
    @Override
    public void changePassword(ChangePasswordRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String current = StrUtil.trimToNull(requestParam.getCurrentPassword());
        String next = StrUtil.trimToNull(requestParam.getNewPassword());
        Assert.notBlank(current, () -> new ClientException("当前密码不能为空"));
        Assert.notBlank(next, () -> new ClientException("新密码不能为空"));

        LoginUser loginUser = UserContext.requireUser();
        UserDO record = userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getId, loginUser.getUserId())
                        .eq(UserDO::getDeleted, 0)
        );
        Assert.notNull(record, () -> new ClientException("用户不存在"));
        if (!PasswordHasher.matches(current, record.getPassword())) {
            throw new ClientException("当前密码不正确");
        }
        record.setPassword(PasswordHasher.hash(next));
        userMapper.updateById(record);
    }

    // 根据 ID 查询未删除的用户记录，不存在时抛出异常
    private UserDO loadById(String id) {
        UserDO record = userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getId, id)
                        .eq(UserDO::getDeleted, 0)
        );
        Assert.notNull(record, () -> new ClientException("用户不存在"));
        return record;
    }

    // 校验目标用户不是默认管理员（admin），防止被误修改或删除
    private void ensureNotDefaultAdmin(UserDO record) {
        if (record != null && DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(record.getUsername())) {
            throw new ClientException("默认管理员不允许修改或删除");
        }
    }

    // 校验用户名唯一性，excludeId 用于排除自身（更新场景）
    private void ensureUsernameAvailable(String username, String excludeId) {
        UserDO existing = userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getUsername, username)
                        .eq(UserDO::getDeleted, 0)
                        .ne(excludeId != null, UserDO::getId, excludeId)
        );
        if (existing != null) {
            throw new ClientException("用户名已存在");
        }
    }

    // 规范化角色值：为空时默认返回 USER，非法值时抛出异常
    private String normalizeRole(String role) {
        String value = StrUtil.trimToNull(role);
        if (StrUtil.isBlank(value)) {
            return UserRole.USER.getCode();
        }
        if (UserRole.ADMIN.getCode().equalsIgnoreCase(value)) {
            return UserRole.ADMIN.getCode();
        }
        if (UserRole.USER.getCode().equalsIgnoreCase(value)) {
            return UserRole.USER.getCode();
        }
        throw new ClientException("角色类型不合法");
    }

    // 将 UserDO 实体转换为 UserVO 视图对象
    private UserVO toVO(UserDO record) {
        return UserVO.builder()
                .id(String.valueOf(record.getId()))
                .username(record.getUsername())
                .role(record.getRole())
                .avatar(record.getAvatar())
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
    }
}
