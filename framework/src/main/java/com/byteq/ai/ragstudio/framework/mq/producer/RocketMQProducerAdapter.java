package com.byteq.ai.ragstudio.framework.mq.producer;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.mq.MessageWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 基于 RocketMQ 的消息生产者实现
 *
 * <p>实现了 {@link MessageQueueProducer} 接口，封装了 RocketMQ 的同步发送和事务消息发送逻辑。
 * 提供统一的日志记录和异常处理，简化业务层的消息发送代码。</p>
 *
 * <p>核心功能：</p>
 * <ul>
 *   <li>自动为消息封装 {@link MessageWrapper}，填充业务 Key、UUID 和时间戳</li>
 *   <li>自动生成唯一 Key（当未提供时）</li>
 *   <li>统一的生产者日志记录（发送结果、消息 ID、业务描述）</li>
 *   <li>事务消息的本地事务注册和发送</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class RocketMQProducerAdapter implements MessageQueueProducer {

    /**
     * RocketMQ 操作模板
     */
    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 事务消息监听器，用于注册本地事务逻辑和处理事务回查
     */
    private final DelegatingTransactionListener transactionListener;

    /**
     * 同步发送普通消息
     * <p>如果未提供 keys，会自动生成 UUID 作为消息的唯一标识。</p>
     *
     * @param topic   目标消息主题
     * @param keys    业务标识 Key，为空则自动生成 UUID
     * @param bizDesc 业务描述信息
     * @param body    业务载荷
     * @return RocketMQ 发送结果
     */
    @Override
    public SendResult send(String topic, String keys, String bizDesc, Object body) {
        // 如果未提供业务 Key，自动生成 UUID
        keys = StrUtil.isEmpty(keys) ? UUID.randomUUID().toString() : keys;

        // 构建消息体，封装业务载荷和元数据
        Message<MessageWrapper<Object>> message = MessageBuilder
                .withPayload(MessageWrapper.builder().keys(keys).body(body).build())
                .setHeader(MessageConst.PROPERTY_KEYS, keys)
                .build();

        SendResult sendResult;
        try {
            // 同步发送消息到 RocketMQ
            sendResult = rocketMQTemplate.syncSend(topic, message);
        } catch (Throwable ex) {
            log.error("[生产者] {} - 消息发送失败，topic: {}, keys: {}", bizDesc, topic, keys, ex);
            throw ex;
        }

        log.info("[生产者] {} - 发送结果: {}, 消息ID: {}, Keys: {}", bizDesc, sendResult.getSendStatus(), sendResult.getMsgId(), keys);
        return sendResult;
    }

    /**
     * 发送事务消息
     * <p>流程：生成事务 ID → 注册本地事务逻辑 → 构建消息 → 发送事务消息。</p>
     * <p>事务的提交/回滚由 RocketMQ 的 {@link DelegatingTransactionListener} 处理。</p>
     *
     * @param topic            目标消息主题
     * @param keys             业务标识 Key
     * @param bizDesc          业务描述信息
     * @param body             业务载荷
     * @param localTransaction 本地事务逻辑，在 half 消息发送成功后执行
     */
    @Override
    public void sendInTransaction(String topic, String keys, String bizDesc, Object body,
                                  Consumer<Object> localTransaction) {
        // 如果未提供业务 Key，自动生成 UUID
        keys = StrUtil.isEmpty(keys) ? UUID.randomUUID().toString() : keys;
        // 生成唯一的事务 ID
        String txId = UUID.randomUUID().toString();

        // 注册本地事务逻辑到监听器，事务 ID 与消息关联
        transactionListener.registerLocalTransaction(txId, localTransaction);

        // 构建事务消息，在消息头中携带事务 ID 和 Topic 信息
        Message<MessageWrapper<Object>> message = MessageBuilder
                .withPayload(MessageWrapper.builder().keys(keys).body(body).build())
                .setHeader(MessageConst.PROPERTY_KEYS, keys)
                .setHeader(DelegatingTransactionListener.HEADER_TX_ID, txId)
                .setHeader(DelegatingTransactionListener.HEADER_TOPIC, topic)
                .build();

        TransactionSendResult sendResult;
        try {
            // 发送 RocketMQ 事务消息
            sendResult = rocketMQTemplate.sendMessageInTransaction(topic, message, null);
        } catch (Throwable ex) {
            log.error("[生产者] {} - 事务消息发送失败，topic: {}, keys: {}", bizDesc, topic, keys, ex);
            throw ex;
        }

        log.info("[生产者] {} - 事务消息发送结果: {}, 本地事务状态: {}, 消息ID: {}, Keys: {}",
                bizDesc, sendResult.getSendStatus(), sendResult.getLocalTransactionState(), sendResult.getMsgId(), keys);
    }
}
