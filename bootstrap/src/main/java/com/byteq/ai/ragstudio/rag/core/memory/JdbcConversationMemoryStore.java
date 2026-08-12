package com.byteq.ai.ragstudio.rag.core.memory;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.byteq.ai.ragstudio.rag.config.MemoryProperties;
import com.byteq.ai.ragstudio.rag.dao.entity.ConversationMessageDO;
import com.byteq.ai.ragstudio.rag.dao.mapper.ConversationMessageMapper;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.rag.service.ConversationMessageService;
import com.byteq.ai.ragstudio.rag.service.ConversationService;
import com.byteq.ai.ragstudio.rag.service.bo.ConversationCreateBO;
import com.byteq.ai.ragstudio.rag.service.bo.ConversationMessageBO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
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
    private final ConversationMessageMapper conversationMessageMapper;
    private final MemoryProperties memoryProperties;

    public JdbcConversationMemoryStore(ConversationService conversationService,
                                       ConversationMessageService conversationMessageService,
                                       ConversationMessageMapper conversationMessageMapper,
                                       MemoryProperties memoryProperties) {
        this.conversationService = conversationService;
        this.conversationMessageService = conversationMessageService;
        this.conversationMessageMapper = conversationMessageMapper;
        this.memoryProperties = memoryProperties;
    }

    /**
     * 从数据库加载指定轮数的对话历史，按时间倒序查询后规范化（移除开头孤立的 assistant 消息）
     * <p>
     * 注意：直接查 Mapper 而非 {@link ConversationMessageService#listMessages}，
     * 以避免 imageUrls 中的 s3:// 协议被替换为预签名 HTTP URL。
     * 保留原始 s3:// URL，让 {@code HttpModelFactory.resolveImageDataUri}
     * 能识别并下载为 base64 发送给 LLM。
     * </p>
     */
    @Override
    public List<ChatMessage> loadHistory(String conversationId, String userId) {
        int maxMessages = resolveMaxHistoryMessages();
        List<ConversationMessageDO> dbMessages = conversationMessageMapper.selectList(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .orderByDesc(ConversationMessageDO::getCreateTime)
                        // 同一毫秒内插入的多条消息（如流式完成落库与标题更新）排序不稳定，
                        // 以 id 作为次级排序保证顺序确定性
                        .orderByDesc(ConversationMessageDO::getId)
                        .last("limit " + maxMessages)
        );
        if (CollUtil.isEmpty(dbMessages)) {
            return List.of();
        }

        Collections.reverse(dbMessages);

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
        // 将 imageUrls 序列化为 JSON 数组字符串
        String imageUrlsJson = null;
        if (message.getImageUrls() != null && !message.getImageUrls().isEmpty()) {
            try {
                imageUrlsJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(message.getImageUrls());
            } catch (Exception e) {
                log.warn("序列化 imageUrls 失败", e);
            }
        }

        Integer tl = message.getThinkingLevel();
        log.debug("存储消息 thinkingLevel={}, role={}, conversationId={}", tl, message.getRole(), conversationId);
        ConversationMessageBO conversationMessage = ConversationMessageBO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(message.getRole().name().toLowerCase())
                .content(message.getContent())
                .thinkingLevel(tl)
                .thinkingContent(message.getThinkingContent())
                .thinkingDuration(message.getThinkingDuration())
                .agentSteps(message.getAgentSteps())
                .citations(message.getCitations())
                .imageUrls(imageUrlsJson)
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

    // 将数据库消息实体转换为 ChatMessage 对象，内容为空且无图片时返回 null
    private ChatMessage toChatMessage(ConversationMessageDO record) {
        if (record == null) return null;
        if (StrUtil.isBlank(record.getContent()) && StrUtil.isBlank(record.getImageUrls())) {
            return null;
        }
        ChatMessage msg = new ChatMessage();
        msg.setRole(ChatMessage.Role.fromString(record.getRole()));
        msg.setContent(record.getContent());
        msg.setThinkingContent(record.getThinkingContent());
        msg.setThinkingDuration(record.getThinkingDuration());
        msg.setAgentSteps(record.getAgentSteps());
        msg.setCitations(record.getCitations());
        // 反序列化 imageUrls JSON（保留原始 s3:// URL，供 LLM 调用层转 base64）
        if (StrUtil.isNotBlank(record.getImageUrls())) {
            try {
                java.util.List<String> urls = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(record.getImageUrls(),
                                new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {});
                msg.setImageUrls(urls);
            } catch (Exception e) {
                log.warn("反序列化 imageUrls 失败: {}", record.getImageUrls(), e);
            }
        }
        return msg;
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

    // 判断消息是否为有效的历史记录消息（有内容或图片即为有效）
    private boolean isHistoryMessage(ChatMessage message) {
        if (message == null) return false;
        if (message.getRole() != ChatMessage.Role.USER && message.getRole() != ChatMessage.Role.ASSISTANT
                && message.getRole() != ChatMessage.Role.OBSERVATION) return false;
        return StrUtil.isNotBlank(message.getContent())
                || (message.getImageUrls() != null && !message.getImageUrls().isEmpty());
    }

    // 根据配置的保留轮数计算最大历史消息条数（一轮 = user + assistant 共2条）
    private int resolveMaxHistoryMessages() {
        int maxTurns = memoryProperties.getHistoryKeepTurns();
        return maxTurns * 2;
    }
}
