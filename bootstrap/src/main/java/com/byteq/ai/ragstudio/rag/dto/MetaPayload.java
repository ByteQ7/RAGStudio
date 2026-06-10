package com.byteq.ai.ragstudio.rag.dto;

/**
 * 元信息事件载荷记录
 * <p>
 * 在 SSE 流式对话开始时推送的元信息事件，包含会话 ID 和任务 ID。
 * 前端收到此事件后可建立对话上下文，用于后续的消息关联和任务取消。
 * </p>
 *
 * @param conversationId 会话 ID，用于标识当前对话
 * @param taskId         任务 ID，用于跟踪和取消当前流式任务
 */
public record MetaPayload(String conversationId, String taskId) {
}
