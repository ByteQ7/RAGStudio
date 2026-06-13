package com.byteq.ai.ragstudio.aimodel.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建 AI 模型请求参数
 */
@Data
public class AiModelCreateRequest {

    /** 供应商 ID */
    @NotNull(message = "供应商ID不能为空")
    private String providerId;

    /** 模型唯一标识 */
    @NotBlank(message = "模型标识不能为空")
    private String modelId;

    /** 供应商侧实际模型名 */
    @NotBlank(message = "模型名称不能为空")
    private String modelName;

    /** 能力类型：CHAT / EMBEDDING / RERANK */
    @NotBlank(message = "能力类型不能为空")
    private String capability;

    /** 是否为默认模型 */
    private Integer isDefault;

    /** 优先级 */
    private Integer priority;

    /** 是否启用（默认 1） */
    private Integer enabled;

    /** 是否支持深度思考 */
    private Integer supportsThinking;

    /** 向量维度（仅 embedding） */
    private Integer dimension;

    /** 自定义 URL */
    private String customUrl;
}
