package com.byteq.ai.ragstudio.aimodel.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 远程模型信息返回对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RemoteModelInfoVO {
    /** 模型标识 */
    private String modelId;
    /** 模型名称 */
    private String modelName;
    /** 能力类型列表 */
    private List<String> capabilities;
    /** 是否支持深度思考 */
    private boolean supportsThinking;
    /** 是否支持多模态 */
    private boolean supportsMultimodal;
    /** 向量维度（仅 embedding） */
    private Integer dimension;
}
