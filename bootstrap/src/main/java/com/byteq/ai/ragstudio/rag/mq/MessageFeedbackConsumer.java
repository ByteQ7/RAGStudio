package com.byteq.ai.ragstudio.rag.mq;

import com.byteq.ai.ragstudio.framework.mq.MessageWrapper;
import com.byteq.ai.ragstudio.rag.mq.event.MessageFeedbackEvent;
import com.byteq.ai.ragstudio.rag.service.MessageFeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 消息反馈 MQ 消费者，负责将点赞/点踩事件异步持久化到数据库
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "message-feedback_topic${unique-name:}",
        consumerGroup = "message-feedback_cg${unique-name:}"
)
public class MessageFeedbackConsumer implements RocketMQListener<MessageWrapper<MessageFeedbackEvent>> {

    private final MessageFeedbackService feedbackService;

    /**
     * 消费消息反馈 MQ 事件
     * <p>
     * 从消息包装中提取点赞/点踩事件，委托给 {@link MessageFeedbackService} 异步持久化到数据库。
     * </p>
     *
     * @param message 消息包装，包含反馈事件体和追踪 keys
     */
    @Override
    public void onMessage(MessageWrapper<MessageFeedbackEvent> message) {
        MessageFeedbackEvent event = message.getBody();

        log.info("[消费者] 开始处理点赞/点踩事件，messageId: {}, userId: {}, vote: {}, keys: {}",
                event.getMessageId(), event.getUserId(), event.getVote(), message.getKeys());
        feedbackService.submitFeedbackByEvent(event);
    }
}
