package com.byteq.ai.ragstudio.rag.service;

import com.byteq.ai.ragstudio.rag.controller.vo.ConversationMessageVO;
import com.byteq.ai.ragstudio.rag.enums.ConversationMessageOrder;
import com.byteq.ai.ragstudio.rag.service.bo.ConversationMessageBO;
import com.byteq.ai.ragstudio.rag.service.bo.ConversationSummaryBO;

import java.util.List;

/**
 * 会话消息服务接口
 * <p>
 * 提供会话消息的添加、查询和摘要管理功能。
 * 消息是 RAG 对话的基本数据单元，包含用户问题和 AI 回答。
 * </p>
 */
public interface ConversationMessageService {

    /**
     * 新增对话消息
     * <p>
     * 将一条对话消息（用户问题或 AI 回答）持久化到数据库。
     * </p>
     *
     * @param conversationMessage 消息业务对象，包含会话 ID、角色、内容等信息
     * @return 新创建消息的 ID
     */
    String addMessage(ConversationMessageBO conversationMessage);

    /**
     * 获取对话消息列表（支持排序与数量限制）
     * <p>
     * 查询指定会话中的消息记录，支持按时间排序和限制返回数量。
     * </p>
     *
     * @param conversationId 会话 ID
     * @param userId         用户 ID
     * @param limit          限制返回的消息数量，为空则返回所有
     * @param order          排序方式（ASC 升序 / DESC 降序）
     * @return 消息视图对象列表
     */
    List<ConversationMessageVO> listMessages(String conversationId, String userId, Integer limit, ConversationMessageOrder order);

    /**
     * 添加对话摘要
     * <p>
     * 为指定对话生成并保存摘要信息，用于对话记忆压缩和快速回顾。
     * </p>
     *
     * @param conversationSummary 摘要业务对象，包含会话 ID、摘要内容等信息
     */
    void addMessageSummary(ConversationSummaryBO conversationSummary);
}
