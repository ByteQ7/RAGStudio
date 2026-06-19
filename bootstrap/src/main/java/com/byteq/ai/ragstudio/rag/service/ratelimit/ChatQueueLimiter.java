package com.byteq.ai.ragstudio.rag.service.ratelimit;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.rag.service.ratelimit.FairDistributedRateLimiter.AcquireRequest;
import com.byteq.ai.ragstudio.framework.web.SseEmitterSender;
import com.byteq.ai.ragstudio.rag.config.MemoryProperties;
import com.byteq.ai.ragstudio.rag.config.RAGRateLimitProperties;
import com.byteq.ai.ragstudio.rag.core.memory.ConversationMemoryService;
import com.byteq.ai.ragstudio.rag.dto.CompletionPayload;
import com.byteq.ai.ragstudio.rag.dto.MessageDelta;
import com.byteq.ai.ragstudio.rag.dto.MetaPayload;
import com.byteq.ai.ragstudio.rag.enums.SSEEventType;
import com.byteq.ai.ragstudio.rag.service.ConversationGroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * SSE 全局并发限流入口
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatQueueLimiter {

    private static final String REJECT_MESSAGE = "系统繁忙，请稍后再试";
    private static final String RESPONSE_TYPE = "response";

    private final FairDistributedRateLimiter chatRateLimiter;
    private final Executor chatEntryExecutor;
    private final RAGRateLimitProperties rateLimitProperties;
    private final ConversationMemoryService memoryService;
    private final ConversationGroupService conversationGroupService;
    private final MemoryProperties memoryProperties;

    /**
     * 将对话请求入队到全局并发限流器
     * <p>
     * 限流关闭时直接提交到线程池；限流开启时通过公平分布式限流器排队，
     * 获取到 permit 后执行 onAcquire 回调，超时则走 reject 流程。
     * </p>
     *
     * @param question       用户问题
     * @param conversationId 会话 ID
     * @param emitter        SSE 发射器
     * @param onAcquire      获取到并发许可后执行的业务逻辑
     */
    public void enqueue(String question, String conversationId, SseEmitter emitter, Runnable onAcquire) {
        if (!Boolean.TRUE.equals(rateLimitProperties.getGlobalEnabled())) {
            try {
                chatEntryExecutor.execute(onAcquire);
            } catch (RejectedExecutionException ex) {
                log.warn("直通分支线程池拒绝任务，转 reject 流程", ex);
                handleReject(question, conversationId, emitter);
            }
            return;
        }

        chatRateLimiter.acquire(AcquireRequest.builder()
                .maxWaitMillis(TimeUnit.SECONDS.toMillis(rateLimitProperties.getGlobalMaxWaitSeconds()))
                .onAcquired(onAcquire)
                .onTimeout(() -> handleReject(question, conversationId, emitter))
                .onAcquiredExecutor(chatEntryExecutor)
                .cancelBinder(cancel -> {
                    emitter.onCompletion(cancel);
                    emitter.onTimeout(cancel);
                    emitter.onError(e -> cancel.run());
                })
                .build());
    }

    // ==================== Reject 业务 ====================

    // 处理被限流拒绝的请求: 记录拒绝会话 -> 向前端发送拒绝事件
    private void handleReject(String question, String conversationId, SseEmitter emitter) {
        RejectedContext context = null;
        try {
            context = recordRejectedConversation(question, conversationId, resolveUserId());
        } catch (Exception ex) {
            // 记录失败不能阻塞 emitter，否则前端永远收不到 DONE
            log.warn("记录 reject 会话失败，仍向前端发送 DONE", ex);
        }
        sendRejectEvents(emitter, context);
    }

    // 记录被拒绝的对话: 保存用户问题和拒绝回复到消息记录，返回拒绝上下文
    private RejectedContext recordRejectedConversation(String question, String conversationId, String userId) {
        if (StrUtil.isBlank(question) || StrUtil.isBlank(userId)) {
            return null;
        }

        String actualConversationId;
        boolean isNewConversation;
        if (StrUtil.isBlank(conversationId)) {
            // 入参未带 conversationId：刚生成的雪花 ID 不可能命中已有会话，跳过 existence 查询
            actualConversationId = IdUtil.getSnowflakeNextIdStr();
            isNewConversation = true;
        } else {
            actualConversationId = conversationId;
            isNewConversation = conversationGroupService.findConversation(actualConversationId, userId) == null;
        }

        memoryService.append(actualConversationId, userId, ChatMessage.user(question));
        String messageId = memoryService.append(actualConversationId, userId, ChatMessage.assistant(REJECT_MESSAGE));

        String title = Strings.EMPTY;
        if (isNewConversation) {
            // append(USER) 内部会触发 conversationService.createOrUpdate（含 LLM 生成标题），此处回查拿到生成结果
            var conversation = conversationGroupService.findConversation(actualConversationId, userId);
            title = conversation != null ? conversation.getTitle() : Strings.EMPTY;
            if (StrUtil.isBlank(title)) {
                title = buildFallbackTitle(question);
            }
        }
        String taskId = IdUtil.getSnowflakeNextIdStr();
        return new RejectedContext(actualConversationId, taskId, messageId, title);
    }

    // 根据用户问题截取生成兜底标题
    private String buildFallbackTitle(String question) {
        if (StrUtil.isBlank(question)) {
            return Strings.EMPTY;
        }
        int maxLen = memoryProperties.getTitleMaxLength() != null ? memoryProperties.getTitleMaxLength() : 30;
        String cleaned = question.trim();
        return cleaned.length() <= maxLen ? cleaned : cleaned.substring(0, maxLen);
    }

    // 向前端发送拒绝相关的 SSE 事件序列（META -> REJECT -> FINISH -> DONE）
    private void sendRejectEvents(SseEmitter emitter, RejectedContext rejectedContext) {
        SseEmitterSender sender = new SseEmitterSender(emitter);
        if (rejectedContext != null) {
            sender.sendEvent(SSEEventType.META.value(), new MetaPayload(rejectedContext.conversationId, rejectedContext.taskId));
            sender.sendEvent(SSEEventType.REJECT.value(), new MessageDelta(RESPONSE_TYPE, REJECT_MESSAGE));
            sender.sendEvent(SSEEventType.FINISH.value(),
                    new CompletionPayload(rejectedContext.messageId != null ? String.valueOf(rejectedContext.messageId) : null, rejectedContext.title));
        }
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
        sender.complete();
    }

    // 解析当前用户 ID，优先从 UserContext 获取，回退到 Sa-Token 登录 ID
    private String resolveUserId() {
        String userId = UserContext.getUserId();
        if (StrUtil.isNotBlank(userId)) {
            return userId;
        }
        try {
            return StpUtil.getLoginIdAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private record RejectedContext(String conversationId, String taskId, String messageId, String title) {
    }
}
