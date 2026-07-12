package com.byteq.ai.ragstudio.rag.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.rag.service.ratelimit.ChatQueueLimiter;
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

    // 发起 SSE 流式对话:
    // 1. 生成会话 ID 和任务 ID
    // 2. 创建 SSE 回调处理器
    // 3. 通过限流器入队，在链路追踪中构建上下文并执行对话管线
    @Override
    public void streamChat(String question, String conversationId, Integer deepThinkingLevel, List<String> knowledgeBaseIds, List<String> imageUrls, SseEmitter emitter) {
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        String taskId = IdUtil.getSnowflakeNextIdStr();
        StreamCallback callback = callbackFactory.createChatEventHandler(emitter, actualConversationId, taskId);

        // 在 callback 被 trace 包装前设置 thinkingLevel
        int finalDeepThinkingLevel = deepThinkingLevel != null ? deepThinkingLevel : 0;
        if (callback instanceof com.byteq.ai.ragstudio.rag.service.handler.StreamChatEventHandler handler) {
            handler.setThinkingLevel(finalDeepThinkingLevel);
        }

        chatQueueLimiter.enqueue(question, actualConversationId, emitter,
                () -> traceRunner.run(question, actualConversationId, taskId, callback, traceAware -> {
                    StreamChatContext ctx = StreamChatContext.builder()
                            .question(question)
                            .conversationId(actualConversationId)
                            .taskId(taskId)
                            .deepThinkingLevel(finalDeepThinkingLevel)
                            .userId(UserContext.getUserId())
                            .callback(traceAware)
                            .knowledgeBaseIds(CollUtil.isEmpty(knowledgeBaseIds) ? List.of() : knowledgeBaseIds)
                            .imageUrls(CollUtil.isEmpty(imageUrls) ? List.of() : imageUrls)
                            .build();
                    chatPipeline.execute(ctx);
                }));
    }

    // 根据任务 ID 停止正在进行的流式对话
    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }
}
