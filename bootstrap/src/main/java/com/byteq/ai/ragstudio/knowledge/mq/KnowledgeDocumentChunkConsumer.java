package com.byteq.ai.ragstudio.knowledge.mq;

import com.byteq.ai.ragstudio.framework.context.LoginUser;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.framework.mq.MessageWrapper;
import com.byteq.ai.ragstudio.knowledge.mq.event.KnowledgeDocumentChunkEvent;
import com.byteq.ai.ragstudio.knowledge.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 文档分块任务 MQ 消费者
 * <p>
 * 负责异步消费 RocketMQ 中的文档分块任务消息，执行耗时的文本提取、文本分块、
 * 向量嵌入（Embedding）及向量库和关系数据库的写入操作。
 * 通过异步消息队列解耦文档上传和分块处理，提升接口响应速度。
 * </p>
 * <p>
 * 处理流程：
 * 1. 从消息体中提取文档 ID（docId）和操作人信息
 * 2. 设置用户上下文，确保操作审计信息完整
 * 3. 调用 documentService.executeChunk() 执行完整的分块流程
 * 4. 清理用户上下文
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "knowledge-document-chunk_topic${unique-name:}",
        consumerGroup = "knowledge-document-chunk_cg${unique-name:}"
)
public class KnowledgeDocumentChunkConsumer implements RocketMQListener<MessageWrapper<KnowledgeDocumentChunkEvent>> {

    private final KnowledgeDocumentService documentService;

    /**
     * 消费文档分块消息
     * <p>接收到 MQ 消息后，执行异步的文档分块处理流程。</p>
     *
     * @param message RocketMQ 消息包装体，包含文档分块事件（docId、kbId、operator）
     */
    @Override
    public void onMessage(MessageWrapper<KnowledgeDocumentChunkEvent> message) {
        KnowledgeDocumentChunkEvent event = message.getBody();

        log.info("[消费者] 开始消费文档分块任务，docId={}, keys={}", event.getDocId(), message.getKeys());

        UserContext.set(LoginUser.builder().username(event.getOperator()).build());
        try {
            documentService.executeChunk(event.getDocId());
        } finally {
            UserContext.clear();
        }
    }
}
