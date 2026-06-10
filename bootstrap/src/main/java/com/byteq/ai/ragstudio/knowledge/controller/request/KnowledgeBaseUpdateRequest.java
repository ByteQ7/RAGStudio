package com.byteq.ai.ragstudio.knowledge.controller.request;

import lombok.Data;

/**
 * 更新知识库请求参数
 * <p>包含知识库 ID、名称和嵌入模型等可更新字段。</p>
 */
@Data
public class KnowledgeBaseUpdateRequest {

    /**
     * 知识库 ID（用于标识要更新的知识库）
     */
    private String id;

    /**
     * 知识库名称（可修改）
     */
    private String name;

    /**
     * 嵌入模型（有文档分块后禁止修改）
     */
    private String embeddingModel;
}
