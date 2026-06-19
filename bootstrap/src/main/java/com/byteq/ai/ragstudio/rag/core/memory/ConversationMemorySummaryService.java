package com.byteq.ai.ragstudio.rag.core.memory;

import com.byteq.ai.ragstudio.framework.convention.ChatMessage;

/**
 * 对话记忆摘要服务接口
 * 负责对话历史的压缩摘要、摘要加载和摘要格式化
 */
public interface ConversationMemorySummaryService {

    /**
     * 判断是否需要压缩对话记忆，如满足条件则异步执行摘要压缩
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     * @param message        当前追加的消息
     */
    void compressIfNeeded(String conversationId, String userId, ChatMessage message);

    /**
     * 加载指定对话的最新摘要
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     * @return 摘要消息，不存在时返回 null
     */
    ChatMessage loadLatestSummary(String conversationId, String userId);

    /**
     * 对摘要消息进行装饰包装（如添加 system 角色标记或模板格式化）
     *
     * @param summary 原始摘要消息
     * @return 装饰后的摘要消息
     */
    ChatMessage decorateIfNeeded(ChatMessage summary);
}
