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
     * 知识库描述
     */
    private String description;

    /**
     * 嵌入模型供应商，如 siliconflow
     */
    private String embeddingProvider;

    /**
     * 嵌入模型，如 qwen-emb-8b
     */
    private String embeddingModel;

    /**
     * 向量维度，不传时由系统根据嵌入模型自动解析
     */
    private Integer dimension;

    /**
     * 向量集合名称
     */
    private String collectionName;

    /**
     * 文档解析引擎：AUTO/LOCAL_MINERU/REMOTE_MINERU/MULTIMODAL_LLM
     * 默认 AUTO（优先 MinerU，失败回退多模态 LLM），仅文本型知识库生效
     */
    private String parseEngine;
}
