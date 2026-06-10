package com.byteq.ai.ragstudio.framework.idempotent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 幂等消费注解
 *
 * <p>防止消息队列消费者重复消费消息。标注在消息消费方法上，
 * 通过 Redis 分布式锁实现幂等控制。</p>
 *
 * <p>工作原理：</p>
 * <ul>
 *   <li>根据 SpEL 表达式生成全局唯一的幂等 Key</li>
 *   <li>通过 Redis 的 SET NX 命令尝试获取锁</li>
 *   <li>如果 Key 已存在且状态为"消费中"，抛出异常触发延迟重试</li>
 *   <li>如果 Key 已存在且状态为"已消费"，直接跳过（幂等）</li>
 *   <li>消费成功后，更新 Key 状态为"已消费"</li>
 *   <li>消费异常时，删除 Key 允许下次正常消费</li>
 * </ul>
 *
 * @see IdempotentConsumeAspect
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotentConsume {

    /**
     * 幂等 Key 前缀
     * <p>用于区分不同业务场景的幂等 Key，避免冲突。</p>
     */
    String keyPrefix() default "";

    /**
     * 幂等 Key 的 SpEL 表达式
     * <p>根据方法参数动态生成唯一的业务标识，例如：{@code #order.orderId}。</p>
     */
    String key();

    /**
     * 幂等 Key 过期时间
     * <p>单位：秒，默认为 1 小时。
     * 超时后 Key 自动过期，允许相同业务标识被重新消费。</p>
     */
    long keyTimeout() default 3600L;
}
