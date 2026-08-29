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
 * 提示词变更历史实体
 * <p>映射数据库表 t_prompt_config_history，每次编辑前将旧内容落历史，
 * 支持查看历史 diff 与回滚到指定版本。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_prompt_config_history")
public class PromptConfigHistoryDO {

    /**
     * 历史主键（自增）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 提示词 key（关联 t_prompt_config.id）
     */
    private String promptId;

    /**
     * 该历史记录对应的版本号
     */
    private Integer version;

    /**
     * 该版本的提示词内容
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
