package com.byteq.ai.ragstudio.aimodel.controller.vo;

import lombok.Data;

import java.util.Date;
import java.util.Map;

/**
 * AI 模型供应商前端返回对象
 */
@Data
public class AiProviderVO {

    private String id;
    private String name;
    private String displayName;
    private String baseUrl;
    /** API 密钥（仅返回掩码，完整密钥不回传前端） */
    private String apiKey;
    /** 是否已配置 API 密钥 */
    private Boolean hasApiKey;
    private Map<String, String> endpoints;
    private Integer enabled;
    /** 供应商图标 URL */
    private String iconUrl;
    /** API 协议类型: openai / dashscope / anthropic */
    private String apiProtocol;
    /** 关联的模型数量 */
    private Integer modelCount;
    private Date createTime;
    private Date updateTime;
}
