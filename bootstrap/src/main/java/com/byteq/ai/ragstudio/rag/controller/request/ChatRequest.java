package com.byteq.ai.ragstudio.rag.controller.request;

import lombok.Data;

import java.util.List;

@Data
public class ChatRequest {

    private String question;

    private String conversationId;

    private Boolean deepThinking = false;

    private List<String> knowledgeBaseIds;
}
