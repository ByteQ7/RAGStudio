package com.byteq.ai.ragstudio.rag.core.memory;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.rag.config.MemoryProperties;
import com.byteq.ai.ragstudio.rag.controller.vo.ConversationMessageVO;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.rag.enums.ConversationMessageOrder;
import com.byteq.ai.ragstudio.rag.service.ConversationMessageService;
import com.byteq.ai.ragstudio.rag.service.ConversationService;
import com.byteq.ai.ragstudio.rag.service.bo.ConversationCreateBO;
import com.byteq.ai.ragstudio.rag.service.bo.ConversationMessageBO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 JDBC 的对话记忆存储实现
 * <p>
 * 直读数据库加载历史消息和追加新消息，每次用户消息追加时同步更新会话记录。
 * </p>
 */
@Slf4j
@Service
public class JdbcConversationMemoryStore implements ConversationMemoryStore {

    private final ConversationService conversationService;
    private final ConversationMessageService conversationMessageService;
    private final MemoryProperties memoryProperties;

    public JdbcConversationMemoryStore(ConversationService conversationService,
                                       ConversationMessageService conversationMessageService,
                                       MemoryProperties memoryProperties) {
        this.conversationService = conversationService;
        this.conversationMessageService = conversationMessageService;
        this.memoryProperties = memoryProperties;
    }

    /**
     * 从数据库加载指定轮数的对话历史，按时间倒序查询后规范化（移除开头孤立的 assistant 消息）
     */
    @Override
    public List<ChatMessage> loadHistory(String conversationId, String userId) {
        int maxMessages = resolveMaxHistoryMessages();
        List<ConversationMessageVO> dbMessages = conversationMessageService.listMessages(
                conversationId,
                userId,
                maxMessages,
                ConversationMessageOrder.DESC
        );
        if (CollUtil.isEmpty(dbMessages)) {
            return List.of();
        }

        List<ChatMessage> result = dbMessages.stream()
                .map(this::toChatMessage)
                .filter(this::isHistoryMessage)
                .collect(Collectors.toList());

        return normalizeHistory(result);
    }

    /**
     * 持久化消息到数据库；若为用户消息则同步创建或更新会话记录
     */
    @Override
    public String append(String conversationId, String userId, ChatMessage message) {
        ConversationMessageBO conversationMessage = ConversationMessageBO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(message.getRole().name().toLowerCase())
                .content(message.getContent())
                .thinkingContent(message.getThinkingContent())
                .thinkingDuration(message.getThinkingDuration())
                .agentSteps(message.getAgentSteps())
                .build();
        String messageId = conversationMessageService.addMessage(conversationMessage);

        if (message.getRole() == ChatMessage.Role.USER) {
            ConversationCreateBO conversation = ConversationCreateBO.builder()
                    .conversationId(conversationId)
                    .userId(userId)
                    .question(message.getContent())
                    .lastTime(new Date())
                    .build();
            conversationService.createOrUpdate(conversation);
        }
        return messageId;
    }

    @Override
    public void refreshCache(String conversationId, String userId) {
        // JDBC 直读模式，无需刷新缓存
    }

    // 将数据库消息记录转换为 ChatMessage 对象，内容为空时返回 null
    private ChatMessage toChatMessage(ConversationMessageVO record) {
        if (record == null || StrUtil.isBlank(record.getContent())) {
            return null;
        }
        return new ChatMessage(
                ChatMessage.Role.fromString(record.getRole()),
                record.getContent(),
                record.getThinkingContent(),
                record.getThinkingDuration(),
                record.getAgentSteps()
        );
    }

    // 规范化历史记录列表，移除开头没有 user 消息的孤立 assistant 消息
    private List<ChatMessage> normalizeHistory(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int start = 0;
        while (start < messages.size() && messages.get(start).getRole() == ChatMessage.Role.ASSISTANT) {
            start++;
        }
        if (start >= messages.size()) {
            return List.of();
        }
        return messages.subList(start, messages.size());
    }

    // 判断消息是否为有效的历史记录消息（角色为 USER 或 ASSISTANT 且内容非空）
    private boolean isHistoryMessage(ChatMessage message) {
        return message != null
                && (message.getRole() == ChatMessage.Role.USER || message.getRole() == ChatMessage.Role.ASSISTANT)
                && StrUtil.isNotBlank(message.getContent());
    }

    // 根据配置的保留轮数计算最大历史消息条数（一轮 = user + assistant 共2条）
    private int resolveMaxHistoryMessages() {
        int maxTurns = memoryProperties.getHistoryKeepTurns();
        return maxTurns * 2;
    }
}
