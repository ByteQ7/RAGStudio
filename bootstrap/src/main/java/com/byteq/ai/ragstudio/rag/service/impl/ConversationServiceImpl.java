package com.byteq.ai.ragstudio.rag.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.byteq.ai.ragstudio.rag.config.MemoryProperties;
import com.byteq.ai.ragstudio.rag.controller.request.ConversationUpdateRequest;
import com.byteq.ai.ragstudio.rag.controller.vo.ConversationVO;
import com.byteq.ai.ragstudio.rag.dao.entity.ConversationDO;
import com.byteq.ai.ragstudio.rag.dao.entity.ConversationMessageDO;
import com.byteq.ai.ragstudio.rag.dao.entity.ConversationSummaryDO;
import com.byteq.ai.ragstudio.rag.dao.mapper.ConversationMapper;
import com.byteq.ai.ragstudio.rag.dao.mapper.ConversationMessageMapper;
import com.byteq.ai.ragstudio.rag.dao.mapper.ConversationSummaryMapper;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.rag.service.ConversationService;
import com.byteq.ai.ragstudio.rag.service.bo.ConversationCreateBO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 会话服务实现类
 * 处理会话的创建、更新、重命名和删除等业务逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final ConversationSummaryMapper summaryMapper;
    private final MemoryProperties memoryProperties;
    private final ConversationTitleGenerator titleGenerator;
    private final Executor memorySummaryExecutor;

    // 根据用户 ID 查询会话列表，按最后活动时间倒序排列
    @Override
    public List<ConversationVO> listByUserId(String userId) {
        if (StrUtil.isBlank(userId)) {
            return List.of();
        }

        List<ConversationDO> records = conversationMapper.selectList(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getDeleted, 0)
                        .orderByDesc(ConversationDO::getLastTime)
        );
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        return records.stream()
                .map(item -> ConversationVO.builder()
                        .conversationId(item.getConversationId())
                        .title(item.getTitle())
                        .groupId(item.getGroupId())
                        .lastTime(item.getLastTime())
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void createOrUpdate(ConversationCreateBO request) {
        String userId = request.getUserId();
        String conversationId = request.getConversationId();
        String question = request.getQuestion();
        if (StrUtil.isBlank(userId)) {
            throw new ClientException("用户信息缺失");
        }

        ConversationDO existing = conversationMapper.selectOne(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getConversationId, conversationId)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getDeleted, 0)
        );

        if (existing == null) {
            // 先写 fallback 标题（问题截断），不阻塞调用方（记忆加载阶段的 TTFT 关键路径）；
            // LLM 标题改为事务提交后异步生成（标题质量与原来一致，只是不再串行阻塞首字）。
            String fallbackTitle = buildFallbackTitle(request.getQuestion());
            ConversationDO record = ConversationDO.builder()
                    .conversationId(conversationId)
                    .userId(userId)
                    .title(fallbackTitle)
                    .lastTime(request.getLastTime())
                    .build();
            conversationMapper.insert(record);
            scheduleAsyncTitleGeneration(record.getConversationId(), userId, request.getQuestion(), fallbackTitle);
            return;
        }

        existing.setLastTime(request.getLastTime());
        conversationMapper.updateById(existing);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void rename(String conversationId, ConversationUpdateRequest request) {
        String userId = UserContext.getUserId();
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            throw new ClientException("会话信息缺失");
        }

        String title = request.getTitle();
        if (StrUtil.isBlank(title)) {
            throw new ClientException("会话名称不能为空");
        }
        int maxLen = memoryProperties.getTitleMaxLength();
        if (title.length() > maxLen) {
            throw new ClientException("会话名称长度不能超过" + maxLen + "个字符");
        }

        ConversationDO record = conversationMapper.selectOne(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getConversationId, conversationId)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getDeleted, 0)
        );
        if (record == null) {
            throw new ClientException("会话不存在");
        }

        record.setTitle(title.trim());
        conversationMapper.updateById(record);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void delete(String conversationId) {
        String userId = UserContext.getUserId();
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            throw new ClientException("会话信息缺失");
        }

        ConversationDO record = conversationMapper.selectOne(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getConversationId, conversationId)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getDeleted, 0)
        );
        if (record == null) {
            throw new ClientException("会话不存在");
        }

        conversationMapper.deleteById(record.getId());
        messageMapper.delete(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getDeleted, 0)
        );
        summaryMapper.delete(
                Wrappers.lambdaQuery(ConversationSummaryDO.class)
                        .eq(ConversationSummaryDO::getConversationId, conversationId)
                        .eq(ConversationSummaryDO::getUserId, userId)
                        .eq(ConversationSummaryDO::getDeleted, 0)
        );
    }

    // 事务提交后调度异步 LLM 标题生成；事务内直接调用会因新线程读不到未提交行而失效
    private void scheduleAsyncTitleGeneration(String conversationId, String userId, String question, String fallbackTitle) {
        Runnable task = () -> {
            try {
                asyncGenerateTitle(conversationId, userId, question, fallbackTitle);
            } catch (Exception e) {
                log.warn("异步生成会话标题失败 - conversationId: {}", conversationId, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    CompletableFuture.runAsync(task, memorySummaryExecutor);
                }
            });
        } else {
            CompletableFuture.runAsync(task, memorySummaryExecutor);
        }
    }

    // 异步生成 LLM 标题并更新会话；条件更新（title 仍为 fallback 才覆盖），
    // 避免覆盖用户重命名，且不会用陈旧 lastTime 覆盖会话最近活跃时间
    private void asyncGenerateTitle(String conversationId, String userId, String question, String fallbackTitle) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId) || StrUtil.isBlank(question)) {
            return;
        }
        String title = titleGenerator.generate(question);
        if (StrUtil.isBlank(title)) {
            return;
        }
        int updated = conversationMapper.update(null,
                Wrappers.lambdaUpdate(ConversationDO.class)
                        .eq(ConversationDO::getConversationId, conversationId)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getDeleted, 0)
                        .eq(ConversationDO::getTitle, fallbackTitle)
                        .set(ConversationDO::getTitle, title));
        if (updated > 0) {
            log.info("异步会话标题生成完成 - conversationId: {}", conversationId);
        }
    }

    // 根据用户问题截取兜底标题（与 ChatQueueLimiter.buildFallbackTitle 逻辑一致）
    private String buildFallbackTitle(String question) {
        if (StrUtil.isBlank(question)) {
            return "新对话";
        }
        int maxLen = memoryProperties.getTitleMaxLength() != null ? memoryProperties.getTitleMaxLength() : 30;
        if (maxLen <= 0) {
            maxLen = 30;
        }
        String cleaned = question.trim();
        return cleaned.length() <= maxLen ? cleaned : cleaned.substring(0, maxLen);
    }

}
