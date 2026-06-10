package com.byteq.ai.ragstudio.knowledge.controller.request;

import lombok.Data;

/**
 * 更新文档请求参数
 * <p>包含文档的可更新字段：名称、处理模式、分块策略、定时同步配置等。</p>
 */
@Data
public class KnowledgeDocumentUpdateRequest {

    /**
     * 文档名称
     */
    private String docName;

    /**
     * 处理模式：chunk / pipeline
     */
    private String processMode;

    /**
     * 分块策略（CHUNK 模式），如 fixed_size、structure_aware
     */
    private String chunkStrategy;

    /**
     * 分块参数 JSON（CHUNK 模式），如 {"chunkSize":512,"overlapSize":128}
     */
    private String chunkConfig;

    /**
     * Pipeline ID（PIPELINE 模式）
     */
    private String pipelineId;

    /**
     * 来源位置（URL，用于更新远程文件地址）
     */
    private String sourceLocation;

    /**
     * 是否开启定时拉取：1-启用，0-禁用
     */
    private Integer scheduleEnabled;

    /**
     * 定时表达式（cron）
     */
    private String scheduleCron;
}
