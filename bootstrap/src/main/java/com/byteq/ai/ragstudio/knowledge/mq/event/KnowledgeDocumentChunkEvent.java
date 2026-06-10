package com.byteq.ai.ragstudio.knowledge.mq.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 文档分块任务事件
 * <p>
 * RocketMQ 事务消息体，用于异步触发文档分块处理。
 * 当文档上传成功后，生产者发送该事件到 MQ，消费者接收后异步执行
 * 文本提取、分块、向量嵌入和写入向量库等耗时操作。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentChunkEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 文档 ID：标识需要分块处理的文档
     */
    private String docId;

    /**
     * 知识库 ID：文档所属的知识库
     */
    private String kbId;

    /**
     * 操作人：发起文档分块操作的用户标识，用于操作审计
     */
    private String operator;
}
