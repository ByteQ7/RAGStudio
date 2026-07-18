package com.byteq.ai.ragstudio.aimodel.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 场景默认模型配置 VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DefaultModelConfigVO {
    /** 主键 ID */
    private String id;
    /** 配置键：chat/summary/title/multimodal/doc_image */
    private String configKey;
    /** 关联 t_ai_model.modelId */
    private String modelId;
    /** 模型名称（冗余展示用） */
    private String modelName;
    /** 供应商名称 */
    private String providerName;
    /** 该模型是否已启用 */
    private Boolean modelEnabled;
    /** 供应商是否有 API Key 配置 */
    private Boolean hasApiKey;
}
