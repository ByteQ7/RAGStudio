package com.byteq.ai.ragstudio.framework.idempotent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 防重复提交注解
 *
 * <p>防止用户重复提交表单信息，通过 Redisson 分布式锁实现。
 * 标注在 Controller 方法上后，同一用户在同一时间内重复提交相同请求将被拦截。</p>
 *
 * <p>幂等策略：</p>
 * <ul>
 *   <li>默认策略：基于 {@code 请求路径 + 用户ID + 参数 MD5} 构建锁 Key</li>
 *   <li>自定义策略：通过 SpEL 表达式指定唯一 Key（优先级更高）</li>
 * </ul>
 *
 * @see IdempotentSubmitAspect
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotentSubmit {

    /**
     * 自定义幂等 Key（SpEL 表达式）
     * <p>优先级高于默认的幂等 Key 生成逻辑。
     * 例如：{@code #order.orderId} 表示基于订单 ID 做幂等。</p>
     */
    String key() default "";

    /**
     * 幂等冲突时的提示消息
     * <p>当检测到重复提交时，返回给客户端的错误提示信息。</p>
     */
    String message() default "您操作太快，请稍后再试";
}
