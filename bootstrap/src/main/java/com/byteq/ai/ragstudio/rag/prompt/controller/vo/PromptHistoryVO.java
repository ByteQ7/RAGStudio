package com.byteq.ai.ragstudio.rag.prompt.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 提示词变更历史视图
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptHistoryVO {

    /**
     * 版本号
     */
    private Integer version;

    /**
     * 该版本内容
     */
    private String content;

    /**
     * 修改人
     */
    private String updatedBy;

    /**
     * 修改时间
     */
    private Date updateTime;
}
