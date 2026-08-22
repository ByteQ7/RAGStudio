package com.byteq.ai.ragstudio.admin.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图谱关系 VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GraphRelationVO {

    private String id;
    private String sourceEntityId;
    private String sourceName;
    private String targetEntityId;
    private String targetName;
    private String predicate;
    private String evidence;
    private Double weight;
    private String docId;
}

