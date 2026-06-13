package com.byteq.ai.ragstudio.aimodel.controller.request;

import lombok.Data;

import java.util.Map;

/**
 * 更新 AI 供应商请求参数
 */
@Data
public class AiProviderUpdateRequest {

    /** 显示名称 */
    private String displayName;

    /** API 基础地址 */
    private String baseUrl;

    /** API 密钥 */
    private String apiKey;

    /** 端点映射 */
    private Map<String, String> endpoints;

    /** 是否启用 */
    private Integer enabled;
}
