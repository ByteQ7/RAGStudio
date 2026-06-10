package com.byteq.ai.ragstudio.rag.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 示例问题视图对象
 * <p>
 * 用于在对话界面展示推荐问题的数据模型，
 * 包含标题、描述和完整的问题文本。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SampleQuestionVO {

    /** 示例问题 ID */
    private String id;

    /** 示例问题标题，简短概括问题内容 */
    private String title;

    /** 问题描述，对问题的补充说明 */
    private String description;

    /** 完整的问题文本 */
    private String question;

    /** 创建时间 */
    private Date createTime;

    /** 最后更新时间 */
    private Date updateTime;
}
