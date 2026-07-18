package com.byteq.ai.ragstudio.aimodel.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 场景默认模型配置实体
 * <p>映射数据库表 t_default_model_config，管理各场景使用的默认模型。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_default_model_config")
public class DefaultModelConfigDO {

    /**
     * 主键 ID（雪花算法自动生成）
     */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 配置键：chat / summary / title / multimodal / doc_image
     */
    private String configKey;

    /**
     * 关联 t_ai_model.modelId
     */
    private String modelId;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;
}
