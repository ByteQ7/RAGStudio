package com.byteq.ai.ragstudio.framework.mq.producer;

import com.byteq.ai.ragstudio.framework.mq.MessageWrapper;

/**
 * 事务消息回查接口
 *
 * <p>按 Topic 注册到 {@link DelegatingTransactionListener}，用于 RocketMQ 事务消息的
 * 回查阶段判断本地事务是否已提交。</p>
 *
 * <p>重要说明：RocketMQ Broker 在触发事务回查时，可能将回查请求发送到任意服务实例。
 * 因此实现类<b>必须</b>基于消息内容（而非内存状态）查询数据库判断本地事务是否已提交，
 * 确保在任何实例上都能得到正确结果。</p>
 */
public interface TransactionChecker {

    /**
     * 检查本地事务是否已提交
     * <p>通过从消息载荷中提取业务参数，查询数据库判断本地事务的执行状态。</p>
     *
     * @param message 消息体，包含业务 Key、载荷等，可从中提取业务参数查询 DB
     * @return {@code true} 表示本地事务已提交（消息可投递），{@code false} 表示已回滚（消息丢弃）
     */
    boolean check(MessageWrapper<?> message);
}
