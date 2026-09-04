package com.byteq.ai.ragstudio.rag.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 对话分组创建请求（创建时仅设置分组名称，指令/知识库等由创建后设置）
 */
@Data
public class ConversationGroupCreateRequest {

    @NotBlank(message = "分组名称不能为空")
    @Size(max = 64, message = "分组名称长度不能超过64个字符")
    private String name;
}
