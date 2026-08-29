package com.byteq.ai.ragstudio.rag.prompt.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 提示词配置实体
 * <p>映射数据库表 t_prompt_config，保存可在后管「提示词管理」页编辑的提示词，
 * 读取时 DB 优先、classpath 默认值兜底。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_prompt_config")
public class PromptConfigDO {

    /**
     * 提示词语义化 key（如 react_system / query_rewrite）
     */
    @TableId(type = IdType.INPUT)
    private String id;

    /**
     * 分类：chat / query / memory / graph / ingestion / tool
     */
    private String category;

    /**
     * 显示名称
     */
    private String name;

    /**
     * 用途说明（后管展示）
     */
    private String description;

    /**
     * 提示词正文（含 section 的模板保存完整文件内容）
     */
    private String content;

    /**
     * 支持的占位符说明，如 {tool_definitions},{kb_context}
     */
    private String variables;

    /**
     * 版本号（每次编辑 +1）
     */
    private Integer version;

    /**
     * 启用开关：false 时回退 classpath 默认值
     */
    private Boolean enabled;

    /**
     * 最后修改人
     */
    private String updatedBy;

    /**
     * 最后修改时间
     */
    private Date updateTime;
}
