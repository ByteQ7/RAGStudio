package com.byteq.ai.ragstudio.rag.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.rag.service.ratelimit.ChatQueueLimiter;
import com.byteq.ai.ragstudio.rag.service.ratelimit.ConversationConcurrencyGate;
import com.byteq.ai.ragstudio.rag.service.ratelimit.DuplicateChatGuard;
import com.byteq.ai.ragstudio.rag.service.RAGChatService;
import com.byteq.ai.ragstudio.rag.service.handler.StreamCallbackFactory;
import com.byteq.ai.ragstudio.rag.service.handler.StreamTaskManager;
import com.byteq.ai.ragstudio.rag.service.pipeline.StreamChatContext;
import com.byteq.ai.ragstudio.rag.service.pipeline.StreamChatPipeline;
import com.byteq.ai.ragstudio.rag.trace.StreamChatTraceRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * RAG 对话服务默认实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGChatServiceImpl implements RAGChatService {

    private final StreamChatPipeline chatPipeline;
    private final ChatQueueLimiter chatQueueLimiter;
    private final StreamCallbackFactory callbackFactory;
    private final StreamChatTraceRunner traceRunner;
    private final StreamTaskManager taskManager;
    private final ConversationConcurrencyGate conversationGate;
    private final DuplicateChatGuard duplicateChatGuard;

    // 发起 SSE 流式对话:
    // 1. 生成会话 ID 和任务 ID
    // 2. 创建 SSE 回调处理器
    // 3. 通过限流器入队，在链路追踪中构建上下文并执行对话管线
    @Override
    public void streamChat(String question, String conversationId, Integer deepThinkingLevel,
                           List<String> knowledgeBaseIds, List<String> imageUrls, String groupId, SseEmitter emitter) {
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        String taskId = IdUtil.getSnowflakeNextIdStr();
        // 在入队前捕获用户 ID（异步线程中 UserContext 可能不可用），供会话并发门闸与重复提交防护使用
        String userId = UserContext.getUserId();
        StreamCallback callback = callbackFactory.createChatEventHandler(emitter, actualConversationId, taskId);

        // 在 callback 被 trace 包装前设置 thinkingLevel
        int finalDeepThinkingLevel = deepThinkingLevel != null ? deepThinkingLevel : 0;
        if (callback instanceof com.byteq.ai.ragstudio.rag.service.handler.StreamChatEventHandler handler) {
            handler.setThinkingLevel(finalDeepThinkingLevel);
        }

        // 新会话重复提交防护：时间窗内同用户同问题的新会话请求直接拒绝（不写历史，问题未被处理）。
        // 已有会话的重复请求不在此限，由会话并发门闸在入队后拦截
        if (StrUtil.isBlank(conversationId) && !duplicateChatGuard.tryAcquire(userId, question)) {
            log.info("新会话重复提交被拒绝: question={}", question);
            chatQueueLimiter.handleRejectWithoutRecord(question, actualConversationId, emitter, taskId);
            return;
        }

        long enqueueStartMillis = System.currentTimeMillis();
        chatQueueLimiter.enqueue(question, actualConversationId, emitter,
                () -> {
                    // 排队耗时可观测：全局限流/线程池繁忙时，首包延迟大头可能来自这里
                    long queueWaitMs = System.currentTimeMillis() - enqueueStartMillis;
                    if (queueWaitMs > 100) {
                        log.info("聊天请求排队等待 {}ms, conversationId={}", queueWaitMs, actualConversationId);
                    }
                    // 会话级并发门闸：同一会话已有请求在处理时直接拒绝，
                    // 避免同会话并发请求导致历史读写竞态（loadAndAppend 非原子、消息乱序）
                    if (!conversationGate.tryAcquire(userId, actualConversationId)) {
                        log.info("同会话并发请求被拒绝，等待前一条消息处理完成: conversationId={}", actualConversationId);
                        // 问题未被处理，拒绝时不写入对话历史，避免污染多轮上下文
                        chatQueueLimiter.handleRejectWithoutRecord(question, actualConversationId, emitter, taskId);
                        return;
                    }
                    try {
                        traceRunner.run(question, actualConversationId, taskId, callback, traceAware -> {
                    StreamChatContext ctx = StreamChatContext.builder()
                            .question(question)
                            .conversationId(actualConversationId)
                            .taskId(taskId)
                            .deepThinkingLevel(finalDeepThinkingLevel)
                            .userId(userId)
                            .groupId(StrUtil.blankToDefault(groupId, null))
                            .callback(traceAware)
                            .knowledgeBaseIds(CollUtil.isEmpty(knowledgeBaseIds) ? List.of() : knowledgeBaseIds)
                            .imageUrls(CollUtil.isEmpty(imageUrls) ? List.of() : imageUrls)
                            .build();
                            chatPipeline.execute(ctx);
                        });
                    } finally {
                        conversationGate.release(userId, actualConversationId);
                    }
                }, taskId);
    }

    // 根据任务 ID 停止正在进行的流式对话
    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }
}
