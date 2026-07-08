package com.byteq.ai.ragstudio.aimodel.controller.vo;

import lombok.Data;

import java.util.Date;

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
    private Integer dimension;
    private String customUrl;
    private Date createTime;
    private Date updateTime;
}
