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
     * 是否支持多模态（图片识别）：1-是，0-否
     */
    private Integer supportsMultimodal;

    /**
     * 是否支持 JSON Output（response_format=json_object）：1-是，0-否
     * <p>仅保证输出合法 JSON，不约束结构（如 deepseek-chat）。</p>
     */
    private Integer supportsJsonOutput;

    /**
     * 是否支持 JSON Schema 结构化输出（response_format=json_schema）：1-是，0-否
     * <p>约束解码，按 schema 强保证输出结构（如 qwen 系列、vLLM 部署的模型）。</p>
     */
    private Integer supportsJsonSchema;

    /**
     * 向量维度列表（JSON 数组，如 "[1024,1536,4096]"），仅 embedding 模型使用
     * <p>多个值表示模型支持多种输出维度，由用户在创建知识库时选择 ≤2000 的值。</p>
     */
    private String dimension;

    /**
     * 自定义 URL（可选，覆盖供应商的 base_url）
     */
    private String customUrl;

    /**
     * API 协议类型覆盖（可选，覆盖供应商的 apiProtocol）：openai / dashscope / anthropic
     */
    private String apiProtocol;

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
