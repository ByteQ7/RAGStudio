package com.byteq.ai.ragstudio.rag.core.memory;

import com.byteq.ai.ragstudio.framework.convention.ChatMessage;

public interface ConversationMemorySummaryService {

    void compressIfNeeded(String conversationId, String userId, ChatMessage message);

    ChatMessage loadLatestSummary(String conversationId, String userId);

    ChatMessage decorateIfNeeded(ChatMessage summary);
}
