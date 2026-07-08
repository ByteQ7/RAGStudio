package com.byteq.ai.ragstudio.rag.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class ChatRequest {

    @Size(max = 10000, message = "问题长度不能超过10000个字符")
    private String question;

    private String conversationId;

    private List<String> knowledgeBaseIds;

    /** 图片 S3 URL 列表（用于多模态识别） */
    private List<String> imageUrls;
}
