package com.byteq.ai.ragstudio.knowledge.controller.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库 Chunk 视图对象
 */
@Data
public class KnowledgeChunkVO {

    /**
     * ID
     */
    private String id;

    /**
     * 知识库ID
     */
    private String kbId;

    /**
     * 文档ID
     */
    private String docId;

    /**
     * 分块序号（从0开始）
     */
    private Integer chunkIndex;

    /**
     * 分块正文内容
     */
    private String content;

    /**
     * 内容哈希（用于幂等/去重）
     */
    private String contentHash;

    /**
     * 字符数
     */
    private Integer charCount;

    /**
     * Token数
     */
    private Integer tokenCount;

    /**
     * 是否启用 0：禁用 1：启用
     */
    private Integer enabled;

    /**
     * 所属知识库名称（全局搜索时填充）
     */
    private String kbName;

    /**
     * 所属文档名称（全局搜索时填充）
     */
    private String docName;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
