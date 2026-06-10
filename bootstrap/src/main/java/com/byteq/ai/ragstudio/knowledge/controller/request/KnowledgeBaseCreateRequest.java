package com.byteq.ai.ragstudio.knowledge.controller.request;

import lombok.Data;

/**
 * 创建知识库请求参数
 * <p>包含创建知识库所需的名称、嵌入模型和向量集合名称信息。</p>
 */
@Data
public class KnowledgeBaseCreateRequest {

    /**
     * 知识库名称
     */
    private String name;

    /**
     * 嵌入模型，如 qwen3-embedding:8b-fp16
     */
    private String embeddingModel;

    /**
     * 向量集合名称
     */
    private String collectionName;
}
