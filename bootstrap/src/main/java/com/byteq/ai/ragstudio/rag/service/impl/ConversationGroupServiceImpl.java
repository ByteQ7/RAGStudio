package com.byteq.ai.ragstudio.rag.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.byteq.ai.ragstudio.rag.dao.entity.ConversationDO;
import com.byteq.ai.ragstudio.rag.dao.entity.ConversationMessageDO;
import com.byteq.ai.ragstudio.rag.dao.entity.ConversationSummaryDO;
import com.byteq.ai.ragstudio.rag.dao.mapper.ConversationMapper;
import com.byteq.ai.ragstudio.rag.dao.mapper.ConversationMessageMapper;
import com.byteq.ai.ragstudio.rag.dao.mapper.ConversationSummaryMapper;
import com.byteq.ai.ragstudio.rag.service.ConversationGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 对话组服务实现类
 * <p>
 * 基于 MyBatis-Plus 实现对话消息、摘要和对话信息的查询功能，
 * 提供按时间范围、ID 范围、数量限制等方式查询对话历史数据。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ConversationGroupServiceImpl implements ConversationGroupService {

    private final ConversationMessageMapper messageMapper;
    private final ConversationSummaryMapper summaryMapper;
    private final ConversationMapper conversationMapper;

    // 获取指定对话中最新的用户消息列表，按创建时间倒序，仅查 role='user'
    @Override
    public List<ConversationMessageDO> listLatestUserOnlyMessages(String conversationId, String userId, int limit) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId) || limit <= 0) {
            return List.of();
        }
        return messageMapper.selectList(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getRole, "user")
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .orderByDesc(ConversationMessageDO::getCreateTime)
                        .last("limit " + limit)
        );
    }

    // 获取指定消息 ID 范围内的消息列表，支持 afterId/beforeId 边界，按 ID 升序
    @Override
    public List<ConversationMessageDO> listMessagesBetweenIds(String conversationId, String userId, String afterId, String beforeId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return List.of();
        }
        var query = Wrappers.lambdaQuery(ConversationMessageDO.class)
                .eq(ConversationMessageDO::getConversationId, conversationId)
                .eq(ConversationMessageDO::getUserId, userId)
                .in(ConversationMessageDO::getRole, "user", "assistant")
                .eq(ConversationMessageDO::getDeleted, 0);
        if (afterId != null) {
            query.gt(ConversationMessageDO::getId, afterId);
        }
        if (beforeId != null) {
            query.lt(ConversationMessageDO::getId, beforeId);
        }
        return messageMapper.selectList(
                query.orderByAsc(ConversationMessageDO::getId)
        );
    }

    // 查找指定时间点之前或当时的最大消息 ID，用于对话摘要定位
    @Override
    public String findMaxMessageIdAtOrBefore(String conversationId, String userId, java.util.Date at) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId) || at == null) {
            return null;
        }
        ConversationMessageDO record = messageMapper.selectOne(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .le(ConversationMessageDO::getCreateTime, at)
                        .orderByDesc(ConversationMessageDO::getId)
                        .last("limit 1")
        );
        return record == null ? null : record.getId();
    }

    // 统计用户在指定对话中发送的消息数量，用于判断对话长度
    @Override
    public long countUserMessages(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return 0;
        }
        return messageMapper.selectCount(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getRole, "user")
                        .eq(ConversationMessageDO::getDeleted, 0)
        );
    }

    // 获取指定对话的最新摘要信息，按 ID 倒序取第一条
    @Override
    public ConversationSummaryDO findLatestSummary(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return null;
        }
        return summaryMapper.selectOne(
                Wrappers.lambdaQuery(ConversationSummaryDO.class)
                        .eq(ConversationSummaryDO::getConversationId, conversationId)
                        .eq(ConversationSummaryDO::getUserId, userId)
                        .eq(ConversationSummaryDO::getDeleted, 0)
                        .orderByDesc(ConversationSummaryDO::getId)
                        .last("limit 1")
        );
    }

    // 根据会话 ID 和用户 ID 查询对话基本信息
    @Override
    public ConversationDO findConversation(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return null;
        }
        return conversationMapper.selectOne(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getConversationId, conversationId)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getDeleted, 0)
        );
    }
}
