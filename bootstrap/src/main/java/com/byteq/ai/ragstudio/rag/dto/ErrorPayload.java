package com.byteq.ai.ragstudio.rag.dto;

/**
 * 流式错误载荷
 * <p>
 * 用于 SSE 流式对话中途出错时向前端推送错误信息，
 * 前端据此将当前消息标记为失败状态并提示用户，避免回答被静默截断。
 * </p>
 *
 * @param error 错误描述文本
 */
public record ErrorPayload(String error) {
}
