package com.byteq.ai.ragstudio.admin.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 图谱实体 VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GraphEntityVO {

    private String id;
    private String canonicalName;
    private String displayName;
    private String entityType;
    private String description;
    private List<String> aliases;
    private Long relationCount;
    private String createTime;
}

