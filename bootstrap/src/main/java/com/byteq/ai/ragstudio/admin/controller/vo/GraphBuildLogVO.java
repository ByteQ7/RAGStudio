package com.byteq.ai.ragstudio.admin.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图谱构建日志 VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GraphBuildLogVO {

    private String id;
    private String triggerType;
    private String docId;
    private String status;
    private Integer entityAdded;
    private Integer entityMerged;
    private Integer relationAdded;
    private Integer relationRemoved;
    private Integer llmCalls;
    private Long durationMs;
    private String errorMessage;
    private String createTime;
}

