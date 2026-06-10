package com.byteq.ai.ragstudio.framework.idempotent;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.google.gson.Gson;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 防止用户重复提交表单的切面控制器
 *
 * <p>通过 Redisson 分布式锁实现接口幂等性，拦截标注了 {@link IdempotentSubmit} 注解的方法，
 * 确保同一用户在同一时间内不会重复提交相同的请求。</p>
 *
 * <p>实现原理：</p>
 * <ul>
 *   <li>基于请求路径、用户 ID 和参数 MD5 构建分布式锁 Key</li>
 *   <li>通过 Redisson 的 {@link RLock#tryLock()} 尝试获取锁</li>
 *   <li>获取锁成功则执行业务逻辑，获取失败则说明是重复提交</li>
 *   <li>业务逻辑执行完毕后释放锁</li>
 * </ul>
 */
@Aspect
@Component
@RequiredArgsConstructor
public final class IdempotentSubmitAspect {

    /**
     * Redisson 客户端，用于创建分布式锁
     */
    private final RedissonClient redissonClient;

    /**
     * JSON 序列化工具，用于将方法参数序列化为字符串以计算 MD5
     */
    private final Gson gson = new Gson();

    /**
     * 增强方法标记 {@link IdempotentSubmit} 注解逻辑
     * <p>在方法执行前尝试获取分布式锁，获取失败则抛出客户端异常提示重复提交。</p>
     *
     * @param joinPoint 切点，包含被拦截方法的信息
     * @return 方法执行结果
     * @throws Throwable 方法执行或幂等检查过程中的异常
     */
    @Around("@annotation(com.byteq.ai.ragstudio.framework.idempotent.IdempotentSubmit)")
    public Object idempotentSubmit(ProceedingJoinPoint joinPoint) throws Throwable {
        IdempotentSubmit idempotentSubmit = getIdempotentSubmitAnnotation(joinPoint);
        // 根据注解配置和方法参数构建分布式锁标识
        String lockKey = buildLockKey(joinPoint, idempotentSubmit);
        RLock lock = redissonClient.getLock(lockKey);
        // 尝试获取锁，获取锁失败就意味着已经重复提交，直接抛出异常
        if (!lock.tryLock()) {
            throw new ClientException(idempotentSubmit.message());
        }
        Object result;
        try {
            // 执行标记了防重复提交注解的方法原逻辑
            result = joinPoint.proceed();
        } finally {
            // 释放分布式锁
            lock.unlock();
        }
        return result;
    }

    /**
     * 从切点中获取 {@link IdempotentSubmit} 注解实例
     *
     * @param joinPoint 切点
     * @return IdempotentSubmit 注解实例
     * @throws NoSuchMethodException 当找不到目标方法时抛出
     */
    public static IdempotentSubmit getIdempotentSubmitAnnotation(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method targetMethod = joinPoint.getTarget().getClass().getDeclaredMethod(methodSignature.getName(), methodSignature.getMethod().getParameterTypes());
        return targetMethod.getAnnotation(IdempotentSubmit.class);
    }

    /**
     * 获取当前请求的 Servlet 路径
     *
     * @return 当前请求的 Servlet 路径（如 {@code /api/order/create}）
     */
    private String getServletPath() {
        ServletRequestAttributes sra = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return Objects.requireNonNull(sra).getRequest().getServletPath();
    }

    /**
     * 获取当前线程中的用户 ID
     *
     * @return 当前登录用户 ID，可能为 null（未登录状态）
     */
    private String getCurrentUserId() {
        return UserContext.getUserId();
    }

    /**
     * 计算方法参数的 MD5 值
     * <p>将方法参数数组序列化为 JSON 字符串后计算 MD5 摘要，
     * 用于区分不同的请求参数。</p>
     *
     * @param joinPoint 切点
     * @return 参数列表的 MD5 十六进制字符串
     */
    private String calcArgsMD5(ProceedingJoinPoint joinPoint) {
        return DigestUtil.md5Hex(gson.toJson(joinPoint.getArgs()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 构建分布式锁的 Key
     * <p>如果注解指定了自定义 Key（SpEL 表达式），则使用自定义 Key；
     * 否则使用默认策略：{@code 请求路径:用户ID:参数MD5} 组合。</p>
     *
     * @param joinPoint          切点
     * @param idempotentSubmit   防重复提交注解
     * @return 分布式锁 Key 字符串
     */
    private String buildLockKey(ProceedingJoinPoint joinPoint, IdempotentSubmit idempotentSubmit) {
        // 如果注解指定了自定义 SpEL 表达式，优先使用
        if (StrUtil.isNotBlank(idempotentSubmit.key())) {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Object keyValue = SpELUtil.parseKey(idempotentSubmit.key(), signature.getMethod(), joinPoint.getArgs());
            return String.format("idempotent-submit:key:%s", keyValue);
        }
        // 使用默认策略：请求路径 + 用户 ID + 参数 MD5
        return String.format(
                "idempotent-submit:path:%s:currentUserId:%s:md5:%s",
                getServletPath(),
                getCurrentUserId(),
                calcArgsMD5(joinPoint)
        );
    }
}
