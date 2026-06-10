package com.byteq.ai.ragstudio.framework.config;

import com.byteq.ai.ragstudio.framework.mq.producer.DelegatingTransactionListener;
import com.byteq.ai.ragstudio.framework.mq.producer.MessageQueueProducer;
import com.byteq.ai.ragstudio.framework.mq.producer.RocketMQProducerAdapter;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ 消息队列自动配置类
 *
 * <p>自动装配 RocketMQ 消息队列相关的 Bean，简化业务模块的配置工作。
 * 当前配置将 {@link MessageQueueProducer} 接口绑定到 RocketMQ 实现，
 * 业务模块通过依赖注入即可使用消息发送功能。</p>
 */
@Configuration
public class RocketMQAutoConfiguration {

    /**
     * 创建 RocketMQ 消息生产者 Bean
     * <p>将 {@link RocketMQTemplate} 和 {@link DelegatingTransactionListener} 注入到
     * {@link RocketMQProducerAdapter} 中，提供同步发送和事务消息发送能力。</p>
     *
     * @param rocketMQTemplate     RocketMQ 原生模板，由 spring-boot-starter-rocketmq 自动配置
     * @param transactionListener  事务消息监听器，负责处理事务消息的二阶段提交和事务回查
     * @return 消息队列生产者实例
     */
    @Bean
    public MessageQueueProducer messageQueueProducer(RocketMQTemplate rocketMQTemplate,
                                                     DelegatingTransactionListener transactionListener) {
        return new RocketMQProducerAdapter(rocketMQTemplate, transactionListener);
    }
}
