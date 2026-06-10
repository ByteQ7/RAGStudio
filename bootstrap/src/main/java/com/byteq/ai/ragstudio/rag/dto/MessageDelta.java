package com.byteq.ai.ragstudio.rag.dto;

/**
 * 消息增量记录
 * <p>
 * 用于表示 SSE 流式推送中的消息类型和增量数据。
 * 在流式对话过程中，AI 的回答是逐步生成的，每个片段作为一个 MessageDelta 推送给前端。
 * </p>
 *
 * @param type  消息类型，如 "thinking"（思考过程）、"text"（回答文本）等
 * @param delta 增量数据，当前片段的内容文本
 */
public record MessageDelta(String type, String delta) {

    /**
     * 获取消息类型
     *
     * @return 消息类型字符串
     */
    public String type() {
        return type;
    }

    /**
     * 获取增量数据
     *
     * @return 增量内容文本
     */
    public String delta() {
        return delta;
    }
}
