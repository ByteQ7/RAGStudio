package com.byteq.ai.ragstudio.framework.context;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.byteq.ai.ragstudio.framework.exception.ClientException;

/**
 * 用户上下文容器
 *
 * <p>基于 Alibaba TransmittableThreadLocal（TTL）实现在线程间透传当前登录用户信息。
 * TTL 相比于普通 InheritableThreadLocal 的优势在于：即使在使用线程池的场景下，
 * 父线程提交任务到线程池时也能将上下文正确拷贝到子线程中。</p>
 *
 * <p>使用场景：</p>
 * <ul>
 *   <li>在 Controller 层拦截器中设置用户上下文</li>
 *   <li>在 Service 层通过 {@link #getUserId()} 获取当前操作用户 ID</li>
 *   <li>在请求结束时调用 {@link #clear()} 清理上下文，避免内存泄漏</li>
 * </ul>
 */
public final class UserContext {

    private static final TransmittableThreadLocal<LoginUser> CONTEXT = new TransmittableThreadLocal<>();

    /**
     * 设置当前线程的用户上下文
     */
    public static void set(LoginUser user) {
        CONTEXT.set(user);
    }

    /**
     * 获取当前线程的用户上下文
     */
    public static LoginUser get() {
        return CONTEXT.get();
    }

    /**
     * 获取当前线程用户，若不存在则抛异常
     */
    public static LoginUser requireUser() {
        LoginUser user = CONTEXT.get();
        if (user == null) {
            throw new ClientException("未获取到当前登录用户");
        }
        return user;
    }

    /**
     * 获取当前用户 ID（未登录返回 null）
     */
    public static String getUserId() {
        LoginUser user = CONTEXT.get();
        return user == null ? null : user.getUserId();
    }

    /**
     * 获取当前用户名（未登录返回 null）
     */
    public static String getUsername() {
        LoginUser user = CONTEXT.get();
        return user == null ? null : user.getUsername();
    }

    /**
     * 获取当前角色（未登录返回 null）
     */
    public static String getRole() {
        LoginUser user = CONTEXT.get();
        return user == null ? null : user.getRole();
    }

    /**
     * 获取当前头像（未登录返回 null）
     */
    public static String getAvatar() {
        LoginUser user = CONTEXT.get();
        return user == null ? null : user.getAvatar();
    }

    /**
     * 清理当前线程的用户上下文
     */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * 判断是否已存在用户上下文
     */
    public static boolean hasUser() {
        return CONTEXT.get() != null;
    }
}
