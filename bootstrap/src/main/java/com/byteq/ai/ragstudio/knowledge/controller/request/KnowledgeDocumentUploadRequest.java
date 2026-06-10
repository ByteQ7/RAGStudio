package com.byteq.ai.ragstudio.knowledge.controller.request;

import lombok.Data;

/**
 * 上传文档请求参数
 * <p>包含文档上传时的来源类型、处理模式、分块策略、定时同步配置等参数。</p>
 */
@Data
public class KnowledgeDocumentUploadRequest {

    /**
     * 来源类型：file（本地文件）/ url（远程 URL 获取）
     */
    private String sourceType;

    /**
     * 来源位置（当 sourceType=url 时填写远程 URL 地址）
     */
    private String sourceLocation;

    /**
     * 是否开启定时拉取（仅 URL 来源支持）
     */
    private Boolean scheduleEnabled;

    /**
     * 定时表达式（cron），用于定时从远程 URL 同步文档更新
     */
    private String scheduleCron;

    /**
     * 处理模式：chunk / pipeline
     * - chunk：使用分块策略直接分块
     * - pipeline：使用数据通道进行清洗处理
     */
    private String processMode;

    /**
     * 分块策略：fixed_size / structure_aware
     * 仅在 processMode=chunk 时有效
     */
    private String chunkStrategy;

    /**
     * 分块参数 JSON，processMode=chunk 时必传
     * 如 {"chunkSize":512,"overlapSize":128} 或 {"targetChars":1400,"maxChars":1800,"minChars":600,"overlapChars":0}
     */
    private String chunkConfig;

    /**
     * 数据通道（Pipeline）ID
     * 仅在 processMode=pipeline 时有效
     */
    private String pipelineId;
}
