package com.byteq.ai.ragstudio.rag.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class ChatRequest {

    @NotBlank(message = "问题不能为空")
    @Size(max = 10000, message = "问题长度不能超过10000个字符")
    private String question;

    private String conversationId;

    private List<String> knowledgeBaseIds;
}
