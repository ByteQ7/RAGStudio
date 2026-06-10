package com.byteq.ai.ragstudio.framework.mq.producer;

import org.apache.rocketmq.client.producer.SendResult;

import java.util.function.Consumer;

/**
 * 消息队列生产者接口
 *
 * <p>定义了消息队列生产者的核心行为规范，支持两种消息发送模式：</p>
 * <ul>
 *   <li><b>普通消息：</b>同步发送，适用于大多数业务消息场景</li>
 *   <li><b>事务消息：</b>二阶段提交，适用于需要保证本地事务与消息发送原子性的场景</li>
 * </ul>
 *
 * <p>当前默认实现为基于 RocketMQ 的 {@link RocketMQProducerAdapter}。</p>
 */
public interface MessageQueueProducer {

    /**
     * 同步发送普通消息
     *
     * @param topic   目标消息主题（Topic）
     * @param keys    业务标识 Key，可用于消息路由和消费端幂等判断
     * @param bizDesc 业务描述信息，用于日志记录和问题追踪
     * @param body    消息业务载荷，将封装为 {@link MessageWrapper} 发送
     * @return RocketMQ 发送结果，包含 msgId、sendStatus 等信息
     */
    SendResult send(String topic, String keys, String bizDesc, Object body);

    /**
     * 发送事务消息
     *
     * <p>事务消息的发送流程：</p>
     * <ol>
     *   <li>发送预备（half）消息到 Broker</li>
     *   <li>half 消息发送成功后执行本地事务（由 {@code localTransaction} 定义）</li>
     *   <li>根据本地事务执行结果提交（commit）或回滚（rollback）消息</li>
     *   <li>如果步骤 2 因异常中断，Broker 会回调 {@link TransactionChecker} 进行事务回查</li>
     * </ol>
     *
     * <p>事务回查逻辑需按 topic 注册到 {@link DelegatingTransactionListener} 中，
     * 通过 {@link DelegatingTransactionListener#registerChecker(String, TransactionChecker)} 注册。</p>
     *
     * @param topic            目标消息主题（Topic）
     * @param keys             业务标识 Key
     * @param bizDesc          业务描述信息，用于日志记录
     * @param body             消息业务载荷
     * @param localTransaction 本地事务逻辑，在 half 消息发送成功后执行；执行抛异常则回滚消息
     */
    void sendInTransaction(String topic, String keys, String bizDesc, Object body,
                           Consumer<Object> localTransaction);
}
