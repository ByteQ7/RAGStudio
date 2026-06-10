package com.byteq.ai.ragstudio.framework.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

/**
 * 消息体包装器
 *
 * <p>统一的消息封装格式，用于在消息队列中传递业务数据。
 * 包装器为每条消息提供了唯一标识、业务 Key、时间戳等元数据，
 * 便于消费端进行幂等处理、延迟计算和消息追踪。</p>
 *
 * <p>使用泛型 {@code <T>} 支持任意类型的业务载荷，适配不同的消息场景。</p>
 *
 * @param <T> 业务载荷类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageWrapper<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务标识 Key
     * <p>用于消息路由和消费端幂等判断，在 RocketMQ 中映射为消息的 keys 属性。</p>
     */
    private String keys;

    /**
     * 业务载荷
     * <p>消息携带的具体业务数据，由生产者填充，消费者解析使用。</p>
     */
    private T body;

    /**
     * 消息唯一标识
     * <p>使用 UUID 生成，用于客户端幂等验证。
     * 每条消息在发送时自动生成唯一 ID，消费者可根据此 ID 去重。</p>
     */
    @Builder.Default
    private String uuid = UUID.randomUUID().toString();

    /**
     * 消息发送时间戳
     * <p>记录消息创建时的系统时间戳（毫秒级），可用于计算消息延迟、判断消息过期等。</p>
     */
    @Builder.Default
    private Long timestamp = System.currentTimeMillis();
}
