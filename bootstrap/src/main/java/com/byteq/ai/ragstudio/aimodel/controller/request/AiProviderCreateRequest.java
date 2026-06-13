package com.byteq.ai.ragstudio.aimodel.controller.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * 创建 AI 供应商请求参数
 */
@Data
public class AiProviderCreateRequest {

    /** 供应商标识（如 bailian、siliconflow、deepseek） */
    @NotBlank(message = "供应商标识不能为空")
    private String name;

    /** 显示名称 */
    private String displayName;

    /** API 基础地址 */
    @NotBlank(message = "API基础地址不能为空")
    private String baseUrl;

    /** API 密钥 */
    private String apiKey;

    /** 端点映射 */
    private Map<String, String> endpoints;

    /** 是否启用（默认 1） */
    private Integer enabled;
}
