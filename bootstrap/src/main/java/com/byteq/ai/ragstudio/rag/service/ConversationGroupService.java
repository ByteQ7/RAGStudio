package com.byteq.ai.ragstudio.rag.service;

import com.byteq.ai.ragstudio.rag.dao.entity.ConversationDO;
import com.byteq.ai.ragstudio.rag.dao.entity.ConversationMessageDO;
import com.byteq.ai.ragstudio.rag.dao.entity.ConversationSummaryDO;

import java.util.Date;
import java.util.List;

/**
 * 对话组服务接口
 * <p>
 * 提供对话消息、摘要和对话信息的查询功能。
 * 用于在 RAG 对话过程中获取历史消息记录、对话摘要和对话基本信息，
 * 支持按时间范围、消息 ID 范围和数量限制等方式查询。
 * </p>
 */
public interface ConversationGroupService {

    /**
     * 获取指定对话中最新的用户消息列表
     * <p>
     * 仅查询用户发送的消息，不包含 AI 回复，用于对话记忆压缩等场景。
     * </p>
     *
     * @param conversationId 会话 ID
     * @param userId         用户 ID
     * @param limit          返回的消息数量限制
     * @return 用户消息列表，按时间倒序排列
     */
    List<ConversationMessageDO> listLatestUserOnlyMessages(String conversationId, String userId, int limit);

    /**
     * 获取指定 ID 范围内的消息列表
     * <p>
     * 根据起始消息 ID 和结束消息 ID 查询范围内的所有消息。
     * </p>
     *
     * @param conversationId 会话 ID
     * @param userId         用户 ID
     * @param afterId        起始消息 ID（不包含该 ID 的消息）
     * @param beforeId       结束消息 ID（不包含该 ID 的消息）
     * @return 指定范围内的消息列表
     */
    List<ConversationMessageDO> listMessagesBetweenIds(String conversationId, String userId, String afterId, String beforeId);

    /**
     * 查找指定时间点之前或当时的最大消息 ID
     * <p>
     * 用于对话摘要定位，找到某个时间点之前最后一条消息的 ID。
     * </p>
     *
     * @param conversationId 会话 ID
     * @param userId         用户 ID
     * @param at             指定的时间点
     * @return 最大消息 ID，如果不存在则返回 null
     */
    String findMaxMessageIdAtOrBefore(String conversationId, String userId, Date at);

    /**
     * 统计用户在指定对话中的消息数量
     * <p>
     * 统计指定用户在指定对话中发送的消息总数，用于判断对话长度等场景。
     * </p>
     *
     * @param conversationId 会话 ID
     * @param userId         用户 ID
     * @return 用户消息总数
     */
    long countUserMessages(String conversationId, String userId);

    /**
     * 获取指定对话的最新摘要信息
     * <p>
     * 查询指定对话的最新对话摘要，用于对话记忆压缩和上下文理解。
     * </p>
     *
     * @param conversationId 会话 ID
     * @param userId         用户 ID
     * @return 最新的对话摘要，如果不存在则返回 null
     */
    ConversationSummaryDO findLatestSummary(String conversationId, String userId);

    /**
     * 查找指定的对话信息
     * <p>
     * 根据会话 ID 和用户 ID 查询对话的基本信息。
     * </p>
     *
     * @param conversationId 会话 ID
     * @param userId         用户 ID
     * @return 对话信息，如果不存在则返回 null
     */
    ConversationDO findConversation(String conversationId, String userId);
}
