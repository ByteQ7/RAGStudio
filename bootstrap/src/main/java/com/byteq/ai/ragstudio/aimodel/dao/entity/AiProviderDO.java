package com.byteq.ai.ragstudio.aimodel.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.byteq.ai.ragstudio.knowledge.dao.handler.JsonbTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * AI 模型供应商实体
 * <p>映射数据库表 t_ai_provider，管理 AI 模型供应商的连接配置。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName(value = "t_ai_provider", autoResultMap = true)
public class AiProviderDO {

    /**
     * 主键 ID（雪花算法自动生成）
     */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 供应商标识（如 bailian、siliconflow、deepseek）
     */
    private String name;

    /**
     * 显示名称（如"阿里百炼"、"DeepSeek"）
     */
    private String displayName;

    /**
     * API 基础地址
     */
    private String baseUrl;

    /**
     * API 密钥
     */
    private String apiKey;

    /**
     * 端点映射（JSON 格式，如 {"chat": "/v1/chat/completions"}）
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String endpoints;

    /**
     * 是否启用：1-启用，0-禁用
     */
    private Integer enabled;

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
