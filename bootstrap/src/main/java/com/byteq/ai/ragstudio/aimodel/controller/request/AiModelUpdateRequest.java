package com.byteq.ai.ragstudio.aimodel.controller.request;

import lombok.Data;

/**
 * 更新 AI 模型请求参数
 */
@Data
public class AiModelUpdateRequest {

    /** 供应商 ID */
    private String providerId;

    /** 供应商侧实际模型名 */
    private String modelName;

    /** 能力类型：CHAT / EMBEDDING / RERANK */
    private String capability;

    /** 优先级 */
    private Integer priority;

    /** 是否启用 */
    private Integer enabled;

    /** 是否支持深度思考 */
    private Integer supportsThinking;

    /** 向量维度（仅 embedding） */
    private Integer dimension;

    /** 自定义 URL */
    private String customUrl;
}
