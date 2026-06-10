package com.byteq.ai.ragstudio.knowledge.mq;

import cn.hutool.json.JSONUtil;
import com.byteq.ai.ragstudio.framework.mq.MessageWrapper;
import com.byteq.ai.ragstudio.framework.mq.producer.DelegatingTransactionListener;
import com.byteq.ai.ragstudio.framework.mq.producer.TransactionChecker;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeDocumentDO;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.byteq.ai.ragstudio.knowledge.enums.DocumentStatus;
import com.byteq.ai.ragstudio.knowledge.mq.event.KnowledgeDocumentChunkEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 文档分块事务消息回查器
 * <p>
 * 实现 RocketMQ 事务消息的回查机制。当生产者发送事务消息后，如果 Broker 未收到
 * 明确的提交或回滚确认，会发起事务回查询问本地事务的执行结果。
 * </p>
 * <p>
 * 回查逻辑：通过查询数据库中文档的状态是否为 RUNNING（处理中）来判断
 * 本地事务是否已成功提交。如果文档状态为 RUNNING 说明事务已提交且正在处理中，
 * 否则说明事务未提交或已回滚。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentChunkTransactionChecker implements TransactionChecker {

    private final KnowledgeDocumentMapper documentMapper;
    private final DelegatingTransactionListener transactionListener;

    @Value("knowledge-document-chunk_topic${unique-name:}")
    private String chunkTopic;

    /**
     * 初始化：向事务监听器注册当前 topic 的事务回查器
     */
    @PostConstruct
    public void init() {
        transactionListener.registerChecker(chunkTopic, this);
    }

    /**
     * 事务回查方法
     * <p>Broker 发起事务回查时调用，通过查询文档状态判断本地事务是否已提交。</p>
     *
     * @param message 事务消息体
     * @return true-事务已提交（文档状态为 RUNNING），false-事务未提交
     */
    @Override
    public boolean check(MessageWrapper<?> message) {
        log.info("[事务回查] 文档分块，消息体：{}", JSONUtil.toJsonStr(message));

        KnowledgeDocumentChunkEvent event = (KnowledgeDocumentChunkEvent) message.getBody();
        String docId = event.getDocId();
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);

        return documentDO != null
                && DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus());
    }
}
