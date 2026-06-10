package com.byteq.ai.ragstudio.rag.service;

import com.byteq.ai.ragstudio.rag.controller.request.ConversationUpdateRequest;
import com.byteq.ai.ragstudio.rag.controller.vo.ConversationVO;
import com.byteq.ai.ragstudio.rag.service.bo.ConversationCreateBO;

import java.util.List;

/**
 * 会话服务接口
 * <p>
 * 提供会话的创建、查询、重命名和删除功能。
 * 会话是 RAG 多轮对话的基本单位，用于组织和存储用户与 AI 之间的交互记录。
 * </p>
 */
public interface ConversationService {

    /**
     * 根据用户 ID 获取会话列表
     * <p>
     * 查询指定用户的所有会话，按最后活动时间倒序排列。
     * </p>
     *
     * @param userId 用户 ID
     * @return 会话视图对象列表
     */
    List<ConversationVO> listByUserId(String userId);

    /**
     * 创建或更新会话
     * <p>
     * 如果 ConversationCreateBO 中的会话 ID 已存在则更新，否则创建新会话。
     * 用于在首次对话时自动创建会话或在后续对话中更新会话信息。
     * </p>
     *
     * @param request 创建或更新会话的业务对象，包含会话 ID、标题等信息
     */
    void createOrUpdate(ConversationCreateBO request);

    /**
     * 重命名会话
     * <p>
     * 允许用户自定义会话标题，方便后续查找和管理。
     * </p>
     *
     * @param conversationId 会话 ID
     * @param request        重命名请求参数，包含新的会话标题
     */
    void rename(String conversationId, ConversationUpdateRequest request);

    /**
     * 删除会话
     * <p>
     * 根据会话 ID 删除指定的会话及其关联的所有消息记录。
     * </p>
     *
     * @param conversationId 会话 ID
     */
    void delete(String conversationId);
}
