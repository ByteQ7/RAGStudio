package com.byteq.ai.ragstudio.admin.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库图谱状态 VO（Graph RAG 总览页表格）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GraphKbStatusVO {

    /**
     * 知识库 ID
     */
    private String kbId;

    /**
     * 知识库名称
     */
    private String kbName;

    /**
     * 实体数
     */
    private Long entityCount;

    /**
     * 关系数
     */
    private Long relationCount;

    /**
     * 已抽取 chunk 数
     */
    private Long extractionCount;

    /**
     * 抽取失败 chunk 数
     */
    private Long failedCount;

    /**
     * 最后构建时间
     */
    private String lastBuildTime;
}