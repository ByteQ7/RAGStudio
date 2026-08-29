package com.byteq.ai.ragstudio.rag.prompt.controller.request;

import lombok.Data;

/**
 * 提示词更新请求（支持部分字段更新）
 */
@Data
public class PromptConfigUpdateRequest {

    /**
     * 显示名称（可选）
     */
    private String name;

    /**
     * 用途说明（可选）
     */
    private String description;

    /**
     * 提示词正文（必填，非空）
     */
    private String content;

    /**
     * 启用开关（可选）
     */
    private Boolean enabled;
}
