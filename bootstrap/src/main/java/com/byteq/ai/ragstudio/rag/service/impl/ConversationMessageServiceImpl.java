package com.byteq.ai.ragstudio.rag.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.byteq.ai.ragstudio.rag.controller.vo.ConversationMessageVO;
import com.byteq.ai.ragstudio.rag.dao.entity.ConversationDO;
import com.byteq.ai.ragstudio.rag.dao.entity.ConversationMessageDO;
import com.byteq.ai.ragstudio.rag.dao.entity.ConversationSummaryDO;
import com.byteq.ai.ragstudio.rag.dao.mapper.ConversationMapper;
import com.byteq.ai.ragstudio.rag.dao.mapper.ConversationMessageMapper;
import com.byteq.ai.ragstudio.rag.dao.mapper.ConversationSummaryMapper;
import com.byteq.ai.ragstudio.rag.enums.ConversationMessageOrder;
import com.byteq.ai.ragstudio.rag.service.MessageFeedbackService;
import com.byteq.ai.ragstudio.rag.service.ConversationMessageService;
import com.byteq.ai.ragstudio.rag.service.bo.ConversationMessageBO;
import com.byteq.ai.ragstudio.rag.service.bo.ConversationSummaryBO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 会话消息服务实现类
 * <p>
 * 基于 MyBatis-Plus 实现会话消息的添加、查询和摘要管理功能，
 * 同时整合用户反馈（点赞/点踩）信息到消息列表中。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ConversationMessageServiceImpl implements ConversationMessageService {

    private final ConversationMessageMapper conversationMessageMapper;
    private final ConversationSummaryMapper conversationSummaryMapper;
    private final ConversationMapper conversationMapper;
    private final MessageFeedbackService feedbackService;

    // 将对话消息（用户问题或 AI 回答）持久化到数据库，返回新消息 ID
    @Override
    public String addMessage(ConversationMessageBO conversationMessage) {
        ConversationMessageDO messageDO = BeanUtil.toBean(conversationMessage, ConversationMessageDO.class);
        conversationMessageMapper.insert(messageDO);
        return messageDO.getId();
    }

    // 获取对话消息列表:
    // 1. 校验会话是否存在
    // 2. 按指定排序和数量限制查询消息记录
    // 3. 批量查询用户对 assistant 消息的反馈（点赞/点踩）
    // 4. 组装消息 VO（含反馈状态）并返回
    @Override
    public List<ConversationMessageVO> listMessages(String conversationId, String userId, Integer limit, ConversationMessageOrder order) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return List.of();
        }

        ConversationDO conversation = conversationMapper.selectOne(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getConversationId, conversationId)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getDeleted, 0)
        );
        if (conversation == null) {
            return List.of();
        }

        boolean asc = order == null || order == ConversationMessageOrder.ASC;
        List<ConversationMessageDO> records = conversationMessageMapper.selectList(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .orderBy(true, asc, ConversationMessageDO::getCreateTime)
                        .last(limit != null, "limit " + limit)
        );
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        if (!asc) {
            Collections.reverse(records);
        }

        List<String> assistantMessageIds = records.stream()
                .filter(record -> "assistant".equalsIgnoreCase(record.getRole()))
                .map(ConversationMessageDO::getId)
                .toList();
        Map<String, Integer> votesByMessageId = feedbackService.getUserVotes(userId, assistantMessageIds);

        List<ConversationMessageVO> result = new ArrayList<>();
        for (ConversationMessageDO record : records) {
            ConversationMessageVO vo = ConversationMessageVO.builder()
                    .id(String.valueOf(record.getId()))
                    .conversationId(record.getConversationId())
                    .role(record.getRole())
                    .content(record.getContent())
                    .thinkingContent(record.getThinkingContent())
                    .thinkingDuration(record.getThinkingDuration())
                    .agentSteps(record.getAgentSteps())
                    .vote(votesByMessageId.get(record.getId()))
                    .createTime(record.getCreateTime())
                    .build();
            result.add(vo);
        }

        return result;
    }

    // 保存对话摘要到数据库，用于对话记忆压缩
    @Override
    public void addMessageSummary(ConversationSummaryBO conversationSummary) {
        ConversationSummaryDO conversationSummaryDO = BeanUtil.toBean(conversationSummary, ConversationSummaryDO.class);
        conversationSummaryMapper.insert(conversationSummaryDO);
    }
}
