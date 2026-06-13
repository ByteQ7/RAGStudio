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
    /** API 密钥（脱敏展示） */
    private String apiKey;
    private Map<String, String> endpoints;
    private Integer enabled;
    private Date createTime;
    private Date updateTime;
}
