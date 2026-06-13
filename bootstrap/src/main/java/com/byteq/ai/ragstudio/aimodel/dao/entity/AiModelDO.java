package com.byteq.ai.ragstudio.aimodel.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * AI 模型配置实体
 * <p>映射数据库表 t_ai_model，管理各供应商下的 AI 模型配置。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_ai_model")
public class AiModelDO {

    /**
     * 主键 ID（雪花算法自动生成）
     */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 供应商 ID（关联 t_ai_provider.id）
     */
    private String providerId;

    /**
     * 模型唯一标识（如 qwen-plus、deepseek-v4-flash）
     */
    private String modelId;

    /**
     * 供应商侧实际模型名（如 qwen-plus-latest）
     */
    private String modelName;

    /**
     * 能力类型：CHAT / EMBEDDING / RERANK
     */
    private String capability;

    /**
     * 是否为该 capability 的默认模型：1-是，0-否
     */
    private Integer isDefault;

    /**
     * 优先级，数字越小优先级越高
     */
    private Integer priority;

    /**
     * 是否启用：1-启用，0-禁用
     */
    private Integer enabled;

    /**
     * 是否支持深度思考：1-是，0-否
     */
    private Integer supportsThinking;

    /**
     * 向量维度（仅 embedding 模型使用）
     */
    private Integer dimension;

    /**
     * 自定义 URL（可选，覆盖供应商的 base_url）
     */
    private String customUrl;

    /**
     * 创建时间（由 MyBatis-Plus 自动填充）
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新时间（由 MyBatis-Plus 自动填充）
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /**
     * 是否删除：0-正常，1-删除（逻辑删除标识）
     */
    @TableLogic
    private Integer deleted;
}
