package com.byteq.ai.ragstudio.rag.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 模型回复完成事件载荷记录
 * <p>
 * 当大语言模型完成回复时，通过此载荷携带完成事件的数据推送给前端。
 * 包含新生成的消息 ID 和可选的会话标题，用于前端更新对话界面。
 * 消息 ID 使用字符串类型以避免前端 JavaScript 处理大整数时的精度丢失问题。
 * </p>
 *
 * @param messageId 消息 ID（使用字符串类型，避免前端精度丢失）
 * @param title     会话标题（可选，首次对话时自动生成）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompletionPayload(String messageId, String title) {
}
