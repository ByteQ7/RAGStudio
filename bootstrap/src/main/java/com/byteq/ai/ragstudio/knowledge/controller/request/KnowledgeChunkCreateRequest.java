package com.byteq.ai.ragstudio.knowledge.controller.request;

import lombok.Data;

/**
 * 知识库 Chunk 创建请求
 */
@Data
public class KnowledgeChunkCreateRequest {

    /**
     * 分块正文内容
     */
    private String content;

    /**
     * 下标
     */
    private Integer index;

    /**
     * 分块 ID
     */
    private String chunkId;

    /**
     * 分块内容类型：TEXT / IMAGE
     */
    private String contentType;

    /**
     * 图片 S3 URL（仅 IMAGE 类型 chunk）
     */
    private String imageUrl;
}
