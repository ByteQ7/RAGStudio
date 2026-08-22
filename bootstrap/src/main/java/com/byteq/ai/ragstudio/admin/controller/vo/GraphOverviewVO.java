package com.byteq.ai.ragstudio.admin.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图谱概览 VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GraphOverviewVO {

    /**
     * 图谱总开关
     */
    private Boolean graphEnabled;

    /**
     * 实体数
     */
    private Long entityCount;

    /**
     * 关系数
     */
    private Long relationCount;

    /**
     * 已抽取 chunk 数（缓存记录数）
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

