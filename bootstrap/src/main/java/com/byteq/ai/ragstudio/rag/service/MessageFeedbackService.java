package com.byteq.ai.ragstudio.rag.service;

import com.byteq.ai.ragstudio.rag.controller.request.MessageFeedbackRequest;
import com.byteq.ai.ragstudio.rag.mq.event.MessageFeedbackEvent;

import java.util.List;
import java.util.Map;

/**
 * 消息反馈服务接口
 * <p>
 * 提供用户对 AI 回复消息的反馈功能，支持点赞/点踩操作。
 * 同时支持同步提交和通过 MQ 异步提交两种方式，以适应不同场景的需求。
 * </p>
 */
public interface MessageFeedbackService {

    /**
     * 提交会话消息反馈（同步，供内部直接调用）
     *
     * @param messageId 消息 ID
     * @param request   反馈内容，包含反馈类型（点赞/点踩）和反馈理由
     */
    void submitFeedback(String messageId, MessageFeedbackRequest request);

    /**
     * 提交会话消息反馈（异步，通过 MQ 持久化）
     * <p>
     * 将反馈事件发送到消息队列，由消费者异步处理持久化，减少请求延迟。
     * </p>
     *
     * @param messageId 消息 ID
     * @param request   反馈内容
     */
    void submitFeedbackAsync(String messageId, MessageFeedbackRequest request);

    /**
     * 通过 MQ 事件异步持久化反馈（由消费者调用）
     * <p>
     * MQ 消费者接收到反馈事件后调用此方法进行实际的持久化操作。
     * </p>
     *
     * @param event 反馈事件，包含消息 ID 和反馈详情
     */
    void submitFeedbackByEvent(MessageFeedbackEvent event);

    /**
     * 查询用户在一批消息上的反馈值
     * <p>
     * 批量查询用户对多条消息的反馈状态，
     * 用于在前端展示用户是否已经对某条消息做出过反馈。
     * </p>
     *
     * @param userId     用户 ID
     * @param messageIds 消息 ID 列表
     * @return 消息 ID 到反馈值的映射（1=点赞，-1=点踩）
     */
    Map<String, Integer> getUserVotes(String userId, List<String> messageIds);
}
