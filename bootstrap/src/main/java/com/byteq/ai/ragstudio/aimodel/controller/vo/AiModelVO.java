package com.byteq.ai.ragstudio.aimodel.controller.vo;

import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * AI 模型配置前端返回对象
 */
@Data
public class AiModelVO {

    private String id;
    private String providerId;
    /** 供应商名称（冗余展示用） */
    private String providerName;
    private String modelId;
    private String modelName;
    private String capability;
    private Integer isDefault;
    private Integer priority;
    private Integer enabled;
    private Integer supportsThinking;
    private Integer supportsMultimodal;
    /** 是否支持 JSON Output（response_format=json_object） */
    private Integer supportsJsonOutput;
    /** 是否支持 JSON Schema 结构化输出（response_format=json_schema） */
    private Integer supportsJsonSchema;
    /** 向量维度列表（如 [1024, 1536, 4096]），仅 embedding */
    private List<Integer> dimension;
    private String customUrl;
    /** API 协议类型覆盖: openai / dashscope / anthropic */
    private String apiProtocol;
    private Date createTime;
    private Date updateTime;
}
