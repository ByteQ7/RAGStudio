package com.byteq.ai.ragstudio.framework.mq.producer;

import com.byteq.ai.ragstudio.framework.mq.MessageWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * 通用的 RocketMQ 事务消息监听器
 *
 * <p>作为 RocketMQ 事务消息的二阶段提交和事务回查的核心处理器，负责：</p>
 * <ul>
 *   <li><b>执行本地事务：</b>在 half 消息发送成功后，执行业务方注册的本地事务逻辑</li>
 *   <li><b>事务回查：</b>当 Broker 未收到明确的 commit/rollback 时，回调查询本地事务状态</li>
 * </ul>
 *
 * <p>设计说明：</p>
 * <ul>
 *   <li>本地事务逻辑通过 {@link #registerLocalTransaction(String, Consumer)} 按消息注册，
 *       仅在当前实例的当前发送过程中有效</li>
 *   <li>事务回查逻辑通过 {@link #registerChecker(String, TransactionChecker)} 按 Topic 注册，
 *       作为 Spring Bean 全局共享，所有实例均可处理回查请求</li>
 *   <li>使用 {@link PlatformTransactionManager} 确保本地事务与 Spring 事务管理器集成</li>
 * </ul>
 */
@Slf4j
@RocketMQTransactionListener
public class DelegatingTransactionListener implements RocketMQLocalTransactionListener {

    /**
     * 消息头中携带的事务上下文 ID
     */
    static final String HEADER_TX_ID = "TRANSACTION_CONTEXT_ID";

    /**
     * 消息头中携带的目标 Topic 名称
     */
    static final String HEADER_TOPIC = "TRANSACTION_TOPIC";

    /**
     * 本地事务执行逻辑注册表
     * <p>Key 为事务 ID（txId），Value 为对应的本地事务执行逻辑。
     * per-message 粒度，仅当前实例有效，事务完成后即移除。</p>
     */
    private final ConcurrentMap<String, Consumer<Object>> localTransactionMap = new ConcurrentHashMap<>();

    /**
     * 事务回查逻辑注册表
     * <p>Key 为 Topic 名称，Value 为对应的回查处理器。
     * per-topic 粒度，所有实例共享（通过 Spring Bean 注册）。</p>
     */
    private final ConcurrentMap<String, TransactionChecker> checkerMap = new ConcurrentHashMap<>();

    /**
     * Spring 事务管理器，用于执行本地事务
     */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * 注册本地事务执行逻辑
     * <p>在发送事务消息前调用，将本地事务逻辑与事务 ID 关联。</p>
     *
     * @param txId             事务唯一标识，与消息头 {@link #HEADER_TX_ID} 对应
     * @param localTransaction 本地事务执行逻辑
     */
    public void registerLocalTransaction(String txId, Consumer<Object> localTransaction) {
        localTransactionMap.put(txId, localTransaction);
    }

    /**
     * 注册事务回查处理器
     * <p>按 Topic 注册回查逻辑，当 Broker 发起事务回查时调用。</p>
     *
     * @param topic   消息主题，与消息头 {@link #HEADER_TOPIC} 对应
     * @param checker 回查处理器，需基于消息内容查询数据库判断事务状态
     */
    public void registerChecker(String topic, TransactionChecker checker) {
        checkerMap.put(topic, checker);
    }

    /**
     * 执行本地事务（RocketMQ 二阶段提交的第一阶段）
     * <p>在 half 消息发送成功后由 RocketMQ 回调此方法，执行实际的业务逻辑。
     * 使用 Spring 的 {@link TransactionTemplate} 包裹执行，确保本地事务与消息一致性。</p>
     *
     * @param message RocketMQ 消息对象，包含消息头和载荷
     * @param arg     回调参数，当前未使用
     * @return 本地事务执行结果：{@code COMMIT} 表示成功，{@code ROLLBACK} 表示失败
     */
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message message, Object arg) {
        // 从消息头中提取事务 ID
        String txId = String.valueOf(message.getHeaders().get(HEADER_TX_ID));
        if ("null".equals(txId)) {
            txId = null;
        }
        // 根据事务 ID 查找并移除对应的本地事务逻辑
        Consumer<Object> localTransaction = txId != null ? localTransactionMap.remove(txId) : null;
        if (localTransaction == null) {
            log.error("[事务消息] 未找到本地事务逻辑, txId={}", txId);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        try {
            // 在 Spring 事务管理器下执行业务逻辑
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> localTransaction.accept(arg));
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            log.error("[事务消息] 本地事务执行失败, txId={}", txId, e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    /**
     * 回调查询本地事务状态（RocketMQ 事务消息补偿机制）
     * <p>当 Broker 长时间未收到 commit/rollback 指令时，会触发回查。
     * 通过消息头中的 Topic 找到对应的 {@link TransactionChecker}，
     * 由检查器基于消息内容查询数据库判断事务是否已提交。</p>
     *
     * @param message RocketMQ 消息对象
     * @return 回查结果：{@code COMMIT} 表示已提交，{@code ROLLBACK} 表示已回滚，{@code UNKNOWN} 表示不确定（等待下次回查）
     */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message message) {
        // 从消息头中提取 Topic 名称
        String topic = String.valueOf(message.getHeaders().get(HEADER_TOPIC));
        if ("null".equals(topic)) {
            topic = null;
        }
        // 根据 Topic 查找已注册的回查处理器
        TransactionChecker checker = topic != null ? checkerMap.get(topic) : null;
        if (checker == null) {
            log.warn("[事务消息] 回查时未找到 topic={} 对应的 checker, 默认 ROLLBACK", topic);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        try {
            // 解析消息载荷，校验类型
            Object rawPayload = message.getPayload();
            if (!(rawPayload instanceof MessageWrapper<?> wrapper)) {
                log.error("[事务消息] 回查时 payload 类型不符合预期: {}", rawPayload != null ? rawPayload.getClass().getName() : "null");
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            // 委托给注册的检查器判断本地事务状态
            boolean committed = checker.check(wrapper);
            RocketMQLocalTransactionState state = committed
                    ? RocketMQLocalTransactionState.COMMIT
                    : RocketMQLocalTransactionState.ROLLBACK;
            log.info("[事务消息] 回查结果: topic={}, state={}", topic, state);
            return state;
        } catch (Exception e) {
            log.error("[事务消息] 回查异常, topic={}", topic, e);
            // 返回 UNKNOWN 让 Broker 稍后再次回查
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }
}
