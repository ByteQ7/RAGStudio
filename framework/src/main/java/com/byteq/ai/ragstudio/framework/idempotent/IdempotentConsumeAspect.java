package com.byteq.ai.ragstudio.framework.idempotent;

import com.byteq.ai.ragstudio.framework.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 防止消息队列消费者重复消费消息的切面控制器
 *
 * <p>通过 AOP 切面拦截标注了 {@link IdempotentConsume} 注解的方法，
 * 使用 Redis 原子操作实现消息消费的幂等控制。</p>
 *
 * <p>核心流程：</p>
 * <ol>
 *   <li>根据 SpEL 表达式和 Key 前缀生成全局唯一的幂等标识</li>
 *   <li>通过 Redis Lua 脚本原子性执行 SET NX + GET + PX 命令</li>
 *   <li>根据 Redis 返回的旧值判断消息是否已被消费或正在被消费</li>
 *   <li>消费成功后更新状态为"已消费"；失败时删除 Key 允许重试</li>
 * </ol>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public final class IdempotentConsumeAspect {

    /**
     * Redis 操作模板
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Redis Lua 幂等脚本
     * <p>原子性地执行：如果 Key 不存在则设置值（NX）并返回旧值（GET），同时设置过期时间（PX）。
     * 这个脚本保证了在并发场景下只有一个线程能成功设置 Key。</p>
     */
    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local value = ARGV[1]
            local expire_time_ms = ARGV[2]
            return redis.call('SET', key, value, 'NX', 'GET', 'PX', expire_time_ms)
            """;

    /**
     * 增强方法标记 {@link IdempotentConsume} 注解逻辑
     * <p>在方法执行前后处理幂等逻辑：执行前检查是否已消费，执行后标记消费状态。</p>
     *
     * @param joinPoint 切点，包含被拦截方法的信息
     * @return 方法执行结果
     * @throws Throwable 方法执行或幂等检查过程中的异常
     */
    @Around("@annotation(com.byteq.ai.ragstudio.framework.idempotent.IdempotentConsume)")
    public Object idempotentConsume(ProceedingJoinPoint joinPoint) throws Throwable {
        // 获取注解信息
        IdempotentConsume idempotentConsume = getIdempotentConsumeAnnotation(joinPoint);
        // 生成全局唯一的幂等 Key
        String uniqueKey = idempotentConsume.keyPrefix()
                + SpELUtil.parseKey(idempotentConsume.key(), ((MethodSignature) joinPoint.getSignature()).getMethod(), joinPoint.getArgs());
        long keyTimeoutSeconds = idempotentConsume.keyTimeout();

        // 执行 Lua 脚本尝试获取锁，原子性检查 Key 是否存在并设置值
        String absentAndGet = stringRedisTemplate.execute(
                RedisScript.of(LUA_SCRIPT, String.class),
                List.of(uniqueKey),
                IdempotentConsumeStatusEnum.CONSUMING.getCode(),
                String.valueOf(TimeUnit.SECONDS.toMillis(keyTimeoutSeconds))
        );

        // 如果已有消费中状态，提示延迟消费；已完成则直接跳过
        boolean errorFlag = IdempotentConsumeStatusEnum.isError(absentAndGet);
        if (errorFlag) {
            log.warn("[{}] MQ repeated consumption, wait for delayed retry.", uniqueKey);
            throw new ServiceException(String.format("消息消费者幂等异常，幂等标识：%s", uniqueKey));
        }
        if (IdempotentConsumeStatusEnum.CONSUMED.getCode().equals(absentAndGet)) {
            log.info("[{}] MQ consumption already completed, skip.", uniqueKey);
            return null;
        }

        try {
            // 执行原始业务逻辑
            Object result = joinPoint.proceed();
            // 消费成功后更新状态为"已消费"
            stringRedisTemplate.opsForValue().set(
                    uniqueKey,
                    IdempotentConsumeStatusEnum.CONSUMED.getCode(),
                    keyTimeoutSeconds,
                    TimeUnit.SECONDS
            );
            return result;
        } catch (Throwable ex) {
            // 消费失败时清除幂等标记，允许下次重试
            try {
                stringRedisTemplate.delete(uniqueKey);
            } catch (Exception ignored) {
                log.warn("[{}] 清除幂等 Key 失败", uniqueKey, ignored);
            }
            throw ex;
        }
    }

    /**
     * 从切点中获取 {@link IdempotentConsume} 注解实例
     *
     * @param joinPoint 切点
     * @return IdempotentConsume 注解实例
     * @throws NoSuchMethodException 当找不到目标方法时抛出
     */
    public static IdempotentConsume getIdempotentConsumeAnnotation(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method targetMethod = joinPoint.getTarget().getClass().getDeclaredMethod(methodSignature.getName(), methodSignature.getMethod().getParameterTypes());
        return targetMethod.getAnnotation(IdempotentConsume.class);
    }
}
