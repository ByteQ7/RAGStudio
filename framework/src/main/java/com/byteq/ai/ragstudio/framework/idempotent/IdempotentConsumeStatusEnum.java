package com.byteq.ai.ragstudio.framework.idempotent;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Objects;

/**
 * 幂等 MQ 消费状态枚举
 *
 * <p>定义了消息消费者在幂等处理中的两种状态：</p>
 * <ul>
 *   <li>{@link #CONSUMING}：消费中，表示消息正在被处理，此时收到相同消息应等待重试</li>
 *   <li>{@link #CONSUMED}：已消费，表示消息已被成功消费，收到相同消息应直接跳过</li>
 * </ul>
 *
 * <p>状态值存储在 Redis 中，配合 {@link IdempotentConsume} 注解使用。</p>
 *
 * @see IdempotentConsume
 * @see IdempotentConsumeAspect
 */
@RequiredArgsConstructor
public enum IdempotentConsumeStatusEnum {

    /**
     * 消费中
     * <p>Redis 中对应的值为 {@code "0"}，表示消息正在被某个消费者处理。</p>
     */
    CONSUMING("0"),

    /**
     * 已消费
     * <p>Redis 中对应的值为 {@code "1"}，表示消息已被成功消费。</p>
     */
    CONSUMED("1");

    @Getter
    private final String code;

    /**
     * 判断消费状态是否为"消费中"（即幂等冲突）
     * <p>如果 Redis 中已存在且值为 CONSUMING，说明有其他消费者正在处理，
     * 应抛出异常触发消息队列的延迟重试机制。</p>
     *
     * @param consumeStatus 从 Redis 中获取到的消费状态值
     * @return {@code true} 表示正处于消费中状态（幂等冲突），{@code false} 表示其他状态
     */
    public static boolean isError(String consumeStatus) {
        return Objects.equals(CONSUMING.code, consumeStatus);
    }
}
