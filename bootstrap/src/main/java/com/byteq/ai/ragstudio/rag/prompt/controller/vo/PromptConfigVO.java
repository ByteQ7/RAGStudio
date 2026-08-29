package com.byteq.ai.ragstudio.rag.prompt.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 提示词配置视图
 * <p>供后管「提示词管理」页展示与编辑。包含当前生效内容与出厂默认内容，
 * source 标识内容来源（db=DB 已自定义 / classpath=回退默认）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptConfigVO {

    /**
     * 提示词语义化 key
     */
    private String key;

    /**
     * 分类：chat / query / memory / graph / ingestion / tool
     */
    private String category;

    /**
     * 显示名称
     */
    private String name;

    /**
     * 用途说明
     */
    private String description;

    /**
     * 当前提示词内容（DB 记录；未自定义时为默认内容）
     */
    private String content;

    /**
     * 出厂默认内容（classpath 模板）
     */
    private String defaultContent;

    /**
     * 支持的占位符说明
     */
    private String variables;

    /**
     * 版本号
     */
    private Integer version;

    /**
     * 启用开关
     */
    private Boolean enabled;

    /**
     * 内容来源：db=DB 已自定义 / classpath=回退默认
     */
    private String source;

    /**
     * 是否与出厂默认不同（便于前端提示「已自定义」）
     */
    private Boolean customized;

    /**
     * 最后修改人
     */
    private String updatedBy;

    /**
     * 最后修改时间
     */
    private Date updateTime;
}
