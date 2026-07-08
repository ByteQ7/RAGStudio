package com.byteq.ai.ragstudio.rag.service.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话消息业务对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationMessageBO {

    /**
     * 对话ID
     */
    private String conversationId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 角色：system/user/assistant
     */
    private String role;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 深度思考内容
     */
    private String thinkingContent;

    /**
     * 深度思考耗时（秒）
     */
    private Integer thinkingDuration;

    /**
     * Agent 推理步骤 JSON
     */
    private String agentSteps;

    /** 引用溯源 JSON */
    private String citations;

    /** 图片 URL 列表 JSON（数组格式，用于多模态消息） */
    private String imageUrls;
}
