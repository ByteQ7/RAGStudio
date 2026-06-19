package com.byteq.ai.ragstudio.rag.enums;

import lombok.RequiredArgsConstructor;

/**
 * SSE 事件类型枚举
 * <p>
 * 定义流式对话中 SSE（Server-Sent Events）推送的各种事件类型。
 * 事件名称与前端约定一致，前端根据事件类型进行不同的处理。
 * </p>
 */
@RequiredArgsConstructor
public enum SSEEventType {

    /**
     * META 事件：会话与任务的元信息事件
     * <p>
     * 在流式对话开始时推送，包含会话 ID 和任务 ID，前端收到后初始化对话上下文。
     * </p>
     */
    META("meta"),

    /**
     * MESSAGE 事件：增量消息事件
     * <p>
     * 在对话过程中持续推送，包含 AI 回答的增量内容文本。
     * </p>
     */
    MESSAGE("message"),

    /**
     * FINISH 事件：模型回复完成事件
     * <p>
     * 当大语言模型完成回复时推送，包含完成状态和消息 ID。
     * </p>
     */
    FINISH("finish"),

    /**
     * DONE 事件：对话完成事件
     * <p>
     * 整个流式对话流程全部完成后推送，前端收到后关闭 SSE 连接。
     * </p>
     */
    DONE("done"),

    /**
     * CANCEL 事件：取消事件
     * <p>
     * 当用户主动取消对话或任务超时被取消时推送。
     * </p>
     */
    CANCEL("cancel"),

    /**
     * REJECT 事件：拒绝事件
     * <p>
     * 当请求被系统拒绝（如限流、参数校验失败等）时推送。
     * </p>
     */
    REJECT("reject"),

    /**
     * AGENT_STEP 事件：Agent 推理步骤事件
     * <p>
     * 当 ReACT Agent 循环中产生新的推理步骤（Thought / Action / Observation / FinalAnswer）时推送。
     * 前端收到后展示 Agent 推理过程。
     * </p>
     */
    AGENT_STEP("agent_step");

    /** SSE 事件名称（与前端约定一致） */
    private final String value;

    /**
     * 获取 SSE 事件名称
     *
     * @return 事件名称字符串
     */
    public String value() {
        return value;
    }
}
