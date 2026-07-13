package com.byteq.ai.ragstudio.rag.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 会话消息视图对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationMessageVO {

    /**
     * 消息ID
     */
    private String id;

    /**
     * 会话ID
     */
    private String conversationId;

    /**
     * 角色 (如: user, assistant)
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
     * 深度思考级别（0-100）
     */
    private Integer thinkingLevel;

    /**
     * 深度思考耗时（秒）
     */
    private Integer thinkingDuration;

    /**
     * Agent 推理步骤 JSON（ReACT 模式下记录中间思考/工具调用过程）
     */
    private String agentSteps;

    /** 引用溯源 JSON */
    private String citations;

    /** 图片 URL 列表 JSON（数组格式，用于多模态消息） */
    private String imageUrls;

    /**
     * 反馈值：1=点赞，-1=点踩，null=未反馈
     */
    private Integer vote;

    /**
     * 创建时间
     */
    private Date createTime;
}
